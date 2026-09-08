package br.com.caixasimples.caixa.internal;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RNF05 para {@code movimento_caixa}, o <strong>membro</strong> do agregado Caixa.
 *
 * <p>Este arquivo esta em {@code caixa.internal} de proposito, pelo mesmo motivo que colocou o
 * {@code IsolamentoDeClienteTest} do R04 no pacote interno: {@code MovimentoCaixaEntity} tem
 * visibilidade de pacote — ninguem de fora consegue nomear o tipo — e a unica coisa que o caminho
 * publico <em>nao</em> consegue mostrar e justamente o {@code conta_id} do membro. Pelo dominio, o
 * teste nao distinguiria um movimento com a conta certa de um com a coluna errada.
 *
 * <p>O par disso e o {@code IsolamentoDeSessaoCaixaTest}, no pacote {@code caixa}, que cobre a raiz
 * enxergando so o que um controller enxergaria.
 */
class MovimentoCaixaDoAgregadoTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private SessaoCaixaRepository sessoes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("o movimento herda a conta da raiz, tambem pelo contexto e nunca por parametro")
    void movimentoRecebeOMesmoTenantDaRaiz() {
        ContaCriada conta = criador.criar("Mercearia Teste", SENHA_DE_TESTE);

        SessaoCaixa sessao = new SessaoCaixa(conta.usuarioId(), Money.de("80.00"));
        sessao.registrar(TipoMovimentoCaixa.SANGRIA, Money.de("20.00"), "Deposito bancario", null);

        TenantContext.executarComo(conta.contaId(), () -> sessoes.save(SessaoCaixaEntity.de(sessao)));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixaEntity gravada = sessoes.findById(sessao.getId()).orElseThrow();

            assertThat(gravada.getMovimentos())
                    .singleElement()
                    .extracting(MovimentoCaixaEntity::getContaId)
                    .as("movimento_caixa tem conta_id proprio, e ele vem do @TenantId — nao de JOIN"
                            + " com a sessao nem de parametro de chamada")
                    .isEqualTo(conta.contaId());
        });
    }

    @Test
    @DisplayName("conta B nao alcanca o movimento da conta A nem pela raiz do agregado")
    void contaNaoEnxergaMovimentoDeOutraConta() {
        ContaCriada contaA = criador.criar("Bar do Teste", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Barbearia Teste", SENHA_DE_TESTE);

        SessaoCaixa sessaoDaContaA = new SessaoCaixa(contaA.usuarioId(), Money.de("40.00"));
        sessaoDaContaA.registrar(TipoMovimentoCaixa.VENDA, Money.de("15.00"), null,
                UUID.randomUUID());

        TenantContext.executarComo(contaA.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(sessaoDaContaA)));

        // Como nao existe MovimentoCaixaRepository (regra 3 do CLAUDE.md), a unica porta para o
        // membro e a raiz — e a raiz ja esta fechada para a conta B. E esse o desenho: menos um
        // caminho de consulta e menos um lugar onde o filtro poderia faltar.
        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(sessoes.findById(sessaoDaContaA.getId())).isEmpty());
    }
}
