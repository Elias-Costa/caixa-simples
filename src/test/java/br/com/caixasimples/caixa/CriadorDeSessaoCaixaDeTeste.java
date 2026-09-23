package br.com.caixasimples.caixa;

import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaEntity;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Grava uma sessão de caixa com movimentos lançados em instantes escolhidos pelo teste.
 *
 * <p>Existe para o fluxo de caixa, cujo período é delimitado pelo instante de cada movimento: o
 * caminho pelos casos de uso grava o instante corrente, e a borda da meia-noite no fuso do balcão
 * não se testa de outro jeito. É o mesmo papel de {@code CriadorDeVendaDeTeste} para a venda, e a
 * sessão é remontada por {@code SessaoCaixa.reconstituir}, como o teste do histórico do caixa faz.
 *
 * <p><strong>A sessão nasce FECHADA</strong>, conferida sem diferença e fechada no instante em
 * que abriu, porque o banco admite uma só sessão ABERTA por operador e o teste de relatório quer
 * várias sessões do mesmo operador no mesmo dia. Nenhum relatório olha o status nem o fechamento;
 * só os movimentos importam.
 *
 * <p>O esperado de fechamento é calculado aqui, com a mesma regra de sinal da raiz, porque
 * {@code reconstituir} não recalcula: ele confia no que está gravado, e a fixture é quem grava.
 *
 * <p>Movimento VENDA ou ESTORNO precisa apontar para uma venda real, porque {@code venda_id} é
 * chave estrangeira; quem chama a obtém de {@code CriadorDeVendaDeTeste}.
 */
public class CriadorDeSessaoCaixaDeTeste {

    private final SessaoCaixaRepository sessoes;

    public CriadorDeSessaoCaixaDeTeste(SessaoCaixaRepository sessoes) {
        this.sessoes = sessoes;
    }

    /**
     * Uma sessão FECHADA do operador informado, aberta no instante e com o valor de abertura
     * dados, com os movimentos informados lançados.
     */
    public UUID criarFechadaComMovimentos(ContaId contaId, UUID usuarioId, Instant abertaEm,
            Money valorAbertura, List<MovimentoCaixa> movimentos) {
        Money esperado = valorAbertura;
        for (MovimentoCaixa movimento : movimentos) {
            esperado = switch (movimento.tipo()) {
                case VENDA, SUPRIMENTO, RECEBIMENTO -> esperado.somar(movimento.valor());
                case SANGRIA, ESTORNO -> esperado.subtrair(movimento.valor());
            };
        }

        SessaoCaixa sessao = SessaoCaixa.reconstituir(UUID.randomUUID(), usuarioId, valorAbertura,
                esperado, esperado, Money.ZERO, abertaEm, abertaEm, StatusSessaoCaixa.FECHADA,
                movimentos);

        return TenantContext.executarComo(contaId, () ->
                sessoes.save(SessaoCaixaEntity.de(sessao)).getId());
    }

    /** Uma sangria ou um suprimento lançado no instante escolhido. */
    public static MovimentoCaixa lancado(TipoMovimentoCaixa tipo, Money valor, String motivo,
            Instant criadoEm) {
        return new MovimentoCaixa(UUID.randomUUID(), tipo, valor, motivo, null, criadoEm);
    }

    /** A VENDA ou o ESTORNO de uma venda, lançado no instante escolhido. */
    public static MovimentoCaixa lancadoDaVenda(TipoMovimentoCaixa tipo, Money valor, UUID vendaId,
            Instant criadoEm) {
        return new MovimentoCaixa(UUID.randomUUID(), tipo, valor, null, vendaId, criadoEm);
    }
}
