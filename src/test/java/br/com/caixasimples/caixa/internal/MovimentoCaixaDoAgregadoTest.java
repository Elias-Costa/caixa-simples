package br.com.caixasimples.caixa.internal;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolamento entre contas (RNF05) para {@code movimento_caixa}, o <strong>membro</strong> do
 * agregado Caixa.
 *
 * <p>Este arquivo está em {@code caixa.internal} de propósito, pelo mesmo motivo que colocou o
 * teste de isolamento de cliente no pacote interno: {@code MovimentoCaixaEntity} tem visibilidade
 * de pacote, ninguém de fora consegue nomear o tipo, e a única coisa que o caminho público
 * <em>não</em> consegue mostrar é justamente o {@code conta_id} do membro. Pelo domínio, o teste
 * não distinguiria um movimento com a conta certa de um com a coluna errada.
 *
 * <p>O par disso é o {@code IsolamentoDeSessaoCaixaTest}, no pacote {@code caixa}, que cobre a raiz
 * enxergando só o que um controller enxergaria.
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
    @DisplayName("o movimento herda a conta da raiz, também pelo contexto e nunca por parâmetro")
    void movimentoRecebeOMesmoTenantDaRaiz() {
        ContaCriada conta = criador.criar("Mercearia Teste", SENHA_DE_TESTE);

        SessaoCaixa sessao = new SessaoCaixa(conta.usuarioId(), Money.de("80.00"));
        sessao.sangrar(Money.de("20.00"), "Deposito bancario");

        TenantContext.executarComo(conta.contaId(), () -> sessoes.save(SessaoCaixaEntity.de(sessao)));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixaEntity gravada = sessoes.findById(sessao.getId()).orElseThrow();

            assertThat(gravada.getMovimentos())
                    .singleElement()
                    .extracting(MovimentoCaixaEntity::getContaId)
                    .as("movimento_caixa tem conta_id próprio, vindo do @TenantId, e não de JOIN"
                            + " com a sessão nem de parâmetro de chamada")
                    .isEqualTo(conta.contaId());
        });
    }

    @Test
    @DisplayName("conta B não alcança o movimento da conta A nem pela raiz do agregado")
    void contaNaoEnxergaMovimentoDeOutraConta() {
        ContaCriada contaA = criador.criar("Bar do Teste", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Barbearia Teste", SENHA_DE_TESTE);

        SessaoCaixa sessaoDaContaA = new SessaoCaixa(contaA.usuarioId(), Money.de("40.00"));
        sessaoDaContaA.suprir(Money.de("15.00"), "Troco inicial");

        TenantContext.executarComo(contaA.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(sessaoDaContaA)));

        // Como não existe repositório para o membro do agregado, a única porta para ele é a raiz, e
        // a raiz já está fechada para a conta B. Esse é o desenho: menos um caminho de consulta é
        // menos um lugar onde o filtro poderia faltar.
        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(sessoes.findById(sessaoDaContaA.getId())).isEmpty());
    }
}
