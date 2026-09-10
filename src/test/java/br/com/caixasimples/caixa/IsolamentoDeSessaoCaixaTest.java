package br.com.caixasimples.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaEntity;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolamento entre contas (RNF05) para {@code sessao_caixa}, no mesmo molde de
 * {@code IsolamentoEntreContasTest}: grava na conta A, consulta como conta B e espera vazio.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}. É o {@code @TenantId} do Hibernate que
 * filtra, e se ele sair de {@code SessaoCaixaEntity} este teste quebra, que é exatamente o ponto
 * dele.
 *
 * <p>Este arquivo fica no pacote {@code caixa} e só enxerga o que um controller enxergaria: a raiz
 * do agregado e o domínio. O {@code conta_id} do <em>membro</em> não se vê daqui, e por isso existe
 * um segundo teste em {@code caixa.internal}.
 */
class IsolamentoDeSessaoCaixaTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private SessaoCaixaRepository sessoes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste criadorDeVenda;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta B não enxerga sessão de caixa da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaSessaoDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);

        UUID sessaoDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(
                        new SessaoCaixa(contaA.usuarioId(), Money.de("150.00")))).getId());

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(sessoes.findById(sessaoDaContaA))
                    .as("findById atravessando tenant")
                    .isEmpty();
            assertThat(sessoes.findAll())
                    .as("listagem da conta B")
                    .extracting(SessaoCaixaEntity::getId)
                    .doesNotContain(sessaoDaContaA);
        });

        // E a conta A continua vendo o próprio dado: o filtro não pode ser esconder de todos.
        TenantContext.executarComo(contaA.contaId(), () -> {
            assertThat(sessoes.findById(sessaoDaContaA)).isPresent();
            assertThat(sessoes.findAll())
                    .extracting(SessaoCaixaEntity::getId)
                    .containsExactly(sessaoDaContaA);
        });
    }

    @Test
    @DisplayName("contaId de uma sessão vem do contexto, nunca de parâmetro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Loja Teste", SENHA_DE_TESTE);

        // Repare que nem o construtor de SessaoCaixa nem o save recebem a conta: não existe
        // assinatura por onde um chamador pudesse informá-la (RNF05).
        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(
                        new SessaoCaixa(conta.usuarioId(), Money.de("0.00")))).getId());

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(sessoes.findById(sessaoId))
                        .get()
                        .extracting(SessaoCaixaEntity::getContaId)
                        .isEqualTo(conta.contaId()));
    }

    @Test
    @DisplayName("o agregado inteiro volta do banco com o mesmo estado que entrou")
    void agregadoSobreviveAoIdaEVolta() {
        ContaCriada conta = criador.criar("Padaria Teste", SENHA_DE_TESTE);

        SessaoCaixa original = new SessaoCaixa(conta.usuarioId(), Money.de("100.00"));
        original.suprir(Money.de("50.00"), "Reforco de troco");
        original.sangrar(Money.de("30.00"), "Pagamento do entregador");

        // Uma chamada de save grava a raiz e os movimentos: o agregado é a unidade transacional,
        // e é o cascade de SessaoCaixaEntity que faz isso valer.
        TenantContext.executarComo(conta.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(original)));

        // O movimento de VENDA aponta para uma venda de verdade, porque desde a migration V7 o
        // banco recusa venda_id que não exista. E a venda, por sua vez, aponta para a sessão; por
        // isso a sessão foi gravada antes, e o terceiro movimento entra numa segunda escrita, pelo
        // mesmo caminho que uma sangria entra no meio do expediente.
        UUID vendaId = criadorDeVenda.criarAbertaEm(conta.contaId(), original.getId(),
                conta.usuarioId());
        original.registrarVenda(Money.de("25.00"), vendaId);
        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixaEntity gravada = sessoes.findById(original.getId()).orElseThrow();
            gravada.atualizarCom(original);
            sessoes.save(gravada);
        });

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa lida = sessoes.findById(original.getId()).orElseThrow().paraDominio();

            assertThat(lida.getId()).isEqualTo(original.getId());
            assertThat(lida.getUsuarioId()).isEqualTo(conta.usuarioId());
            assertThat(lida.getValorAbertura()).isEqualTo(Money.de("100.00"));
            assertThat(lida.getStatus()).isEqualTo(StatusSessaoCaixa.ABERTA);
            assertThat(lida.getValorFechamentoContado()).isNull();
            assertThat(lida.getDiferenca()).isNull();
            assertThat(lida.getFechadaEm()).isNull();

            // A invariante do agregado atravessou o banco: 100 mais 25 mais 50 menos 30.
            assertThat(lida.getValorFechamentoEsperado()).isEqualTo(Money.de("145.00"));

            assertThat(lida.getMovimentos())
                    .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor, MovimentoCaixa::motivo,
                            MovimentoCaixa::vendaId)
                    .containsExactlyInAnyOrder(
                            tuple(TipoMovimentoCaixa.VENDA, Money.de("25.00"), null, vendaId),
                            tuple(TipoMovimentoCaixa.SUPRIMENTO, Money.de("50.00"),
                                    "Reforco de troco", null),
                            tuple(TipoMovimentoCaixa.SANGRIA, Money.de("30.00"),
                                    "Pagamento do entregador", null));

            // O @OrderBy da entidade, afirmado sem depender de empate de relógio: três chamadas
            // seguidas de Instant.now() podem cair no mesmo microssegundo, e uma comparação
            // posicional viraria teste intermitente por causa disso.
            assertThat(lida.getMovimentos())
                    .extracting(MovimentoCaixa::criadoEm)
                    .isSorted();
        });
    }
}
