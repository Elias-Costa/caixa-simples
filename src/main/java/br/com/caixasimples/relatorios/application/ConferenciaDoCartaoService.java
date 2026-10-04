package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.relatorios.internal.LancamentoEmCartao;
import br.com.caixasimples.relatorios.internal.VendaParaRelatorioRepository;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.vendas.StatusVenda;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A conferência do cartão: os pagamentos em cartão de um dia, com o NSU de cada um, para o dono
 * bater com o extrato da operadora.
 *
 * <p><strong>Por que existe.</strong> O cartão é lançado à mão (RF26) e não passa pela gaveta, então
 * uma venda paga em dinheiro e lançada como cartão deixa o dinheiro sair sem que o fechamento do
 * caixa (RF15) acuse diferença. O faturamento filtrado por cartão (RF24) só confere o total; esta
 * lista confere pagamento por pagamento, e aponta o que veio sem NSU.
 *
 * <p><strong>Um dia só, e não um período.</strong> A lista não é soma: num mês, passaria de
 * centenas de linhas. E é a conferência do extrato do dia, que a maquininha organiza pela hora da
 * cobrança; por isso o dia de cada linha é o do lançamento do pagamento, no fuso do balcão, e não o
 * da conclusão da venda.
 *
 * <p><strong>Entram as parcelas de venda de toda situação e os recebimentos de fiado</strong>, cada
 * linha com a situação atual da venda: o extrato mostra a cobrança da venda cancelada depois e da
 * comanda que ficou aberta, e sem a linha ela ficaria sem par. Na venda cancelada, o dono deve achar
 * também um estorno na maquininha.
 *
 * <p>Só lê, e a conta vem sempre do contexto (RNF05). <strong>É relatório do administrador
 * (RF30)</strong>, como os outros, e pede o plano com relatórios; a exigência do NSU, que é da
 * venda, não pede plano nenhum, e o NSU anotado antes do upgrade aparece aqui depois dele.
 */
@Service
public class ConferenciaDoCartaoService {

    private final VendaParaRelatorioRepository vendas;
    private final ContaService contas;

    ConferenciaDoCartaoService(VendaParaRelatorioRepository vendas, ContaService contas) {
        this.vendas = vendas;
        this.contas = contas;
    }

    /**
     * Os pagamentos em cartão de um dia do balcão, de todos os operadores, em ordem de lançamento.
     *
     * @param dia o dia do balcão, obrigatório
     */
    @Transactional(readOnly = true)
    public ConferenciaDoCartao doDia(LocalDate dia) {
        return consultar(dia, null);
    }

    /**
     * Os pagamentos em cartão de um dia, <strong>só os de um operador</strong> (RF24): na parcela,
     * o operador da venda; no recebimento de fiado, quem o recebeu no próprio caixa.
     *
     * <p>Duas assinaturas, e não um parâmetro que aceita nulo, como no ranking dos mais vendidos:
     * quem chama diz qual das duas perguntas está fazendo. Operador que não existe, ou que é de
     * outra conta, dá lista vazia, e não erro.
     *
     * @param dia        o dia do balcão, obrigatório
     * @param operadorId o operador, obrigatório nesta assinatura
     */
    @Transactional(readOnly = true)
    public ConferenciaDoCartao doDia(LocalDate dia, UUID operadorId) {
        Objects.requireNonNull(operadorId,
                "operadorId nao pode ser nulo; sem operador, use a assinatura sem ele");
        return consultar(dia, operadorId);
    }

    /** A lista em si; {@code operadorId} nulo é a conta inteira, e só as duas públicas chamam. */
    private ConferenciaDoCartao consultar(LocalDate dia, UUID operadorId) {
        UsuarioContext.exigirAdmin();
        contas.exigirRecurso(RecursoDoPlano.RELATORIOS);
        Periodo umDia = new Periodo(dia, dia);

        List<Lancamento> lancamentos = new ArrayList<>();
        for (LancamentoEmCartao parcela : vendas.parcelasLancadasEntre(FormaPagamento.CARTAO,
                umDia.inicioInclusivo(), umDia.fimExclusivo(), operadorId)) {
            lancamentos.add(Lancamento.de(Origem.PARCELA, parcela));
        }
        for (LancamentoEmCartao recebimento : vendas.recebimentosLancadosEntre(
                FormaPagamento.CARTAO, umDia.inicioInclusivo(), umDia.fimExclusivo(),
                operadorId)) {
            lancamentos.add(Lancamento.de(Origem.RECEBIMENTO, recebimento));
        }
        // Cada consulta já vem em ordem; juntas, as duas listas se intercalam pela hora, e o id
        // desempata como nas consultas.
        lancamentos.sort(Comparator.comparing(Lancamento::lancadoEm)
                .thenComparing(Lancamento::id));
        return new ConferenciaDoCartao(dia, lancamentos);
    }

    /** De onde veio a linha: a parcela de uma venda ou o recebimento de um fiado. */
    public enum Origem {
        PARCELA, RECEBIMENTO
    }

    /**
     * A resposta do caso de uso, aninhada no serviço como a dos outros relatórios.
     *
     * @param dia         o dia do balcão consultado
     * @param lancamentos em ordem de lançamento; vazia quando não houve cartão, nunca nula
     */
    public record ConferenciaDoCartao(LocalDate dia, List<Lancamento> lancamentos) {
    }

    /**
     * Um pagamento em cartão da lista.
     *
     * @param id              a parcela ou o recebimento
     * @param origem          parcela de venda ou recebimento de fiado
     * @param vendaId         a venda a que o pagamento pertence
     * @param lancadoEm       quando foi lançado
     * @param valor           o valor cobrado na maquininha
     * @param operadorId      quem lançou; o nome é do módulo de contas, e a tela já o tem
     * @param situacaoDaVenda a situação atual da venda
     * @param nsu             o NSU do comprovante da maquininha; nulo marca o que falta conferir à
     *                        mão
     */
    public record Lancamento(UUID id, Origem origem, UUID vendaId, Instant lancadoEm, Money valor,
            UUID operadorId, StatusVenda situacaoDaVenda, String nsu) {

        static Lancamento de(Origem origem, LancamentoEmCartao linha) {
            return new Lancamento(linha.id(), origem, linha.vendaId(), linha.lancadoEm(),
                    Money.de(linha.valor()), linha.usuarioId(), linha.situacaoDaVenda(),
                    linha.nsu());
        }
    }
}
