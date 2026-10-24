package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.relatorios.internal.LancamentoEmPix;
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
 * A conferência do Pix: todo Pix de um dia, das vendas e dos recebimentos de fiado, para o dono
 * bater com o extrato do banco.
 *
 * <p><strong>Por que existe.</strong> O fiado recebido em dinheiro aparece no fluxo de caixa, e o
 * recebido em cartão, na conferência do cartão; o recebido em Pix não aparecia em lugar nenhum, e o
 * Pix do dia não fechava com o extrato. Somar o recebimento ao faturamento por forma contaria o fiado
 * duas vezes, porque ele já entra no dia da venda: esta lista não soma, confere linha por linha.
 *
 * <p><strong>No molde da conferência do cartão.</strong> Um dia só, pelo dia do lançamento no fuso do
 * balcão, que é como o extrato organiza; as vendas de toda situação, com a situação na linha, porque o
 * Pix da venda cancelada depois também está no extrato; o operador opcional, como no resto dos
 * relatórios (RF24). O Pix lançado à mão não tem identificador, e a conferência casa pelo valor e pela
 * hora.
 *
 * <p><strong>A cobrança integrada que ainda não teve o pagamento confirmado entra marcada</strong>: o
 * banco pode já ter o crédito que o aviso do provedor não trouxe, e a linha marcada leva à
 * conciliação. A recusada, expirada ou removida, fica fora, porque nela nunca entrou dinheiro.
 *
 * <p>Só lê, e a conta vem sempre do contexto (RNF05). É relatório do administrador (RF30) e pede o
 * plano com relatórios, como os outros.
 */
@Service
public class ConferenciaDoPixService {

    private final VendaParaRelatorioRepository vendas;
    private final ContaService contas;

    ConferenciaDoPixService(VendaParaRelatorioRepository vendas, ContaService contas) {
        this.vendas = vendas;
        this.contas = contas;
    }

    /**
     * Os Pix de um dia do balcão, de todos os operadores, em ordem de lançamento.
     *
     * @param dia o dia do balcão, obrigatório
     */
    @Transactional(readOnly = true)
    public ConferenciaDoPix doDia(LocalDate dia) {
        return consultar(dia, null);
    }

    /**
     * Os Pix de um dia, <strong>só os de um operador</strong> (RF24): na parcela, o operador da
     * venda; no recebimento de fiado, quem o recebeu no próprio caixa. Operador que não existe, ou
     * que é de outra conta, dá lista vazia, e não erro.
     *
     * @param dia        o dia do balcão, obrigatório
     * @param operadorId o operador, obrigatório nesta assinatura
     */
    @Transactional(readOnly = true)
    public ConferenciaDoPix doDia(LocalDate dia, UUID operadorId) {
        Objects.requireNonNull(operadorId,
                "operadorId nao pode ser nulo; sem operador, use a assinatura sem ele");
        return consultar(dia, operadorId);
    }

    /** A lista em si; {@code operadorId} nulo é a conta inteira, e só as duas públicas chamam. */
    private ConferenciaDoPix consultar(LocalDate dia, UUID operadorId) {
        UsuarioContext.exigirAdmin();
        contas.exigirRecurso(RecursoDoPlano.RELATORIOS);
        Periodo umDia = new Periodo(dia, dia);

        List<Lancamento> lancamentos = new ArrayList<>();
        for (LancamentoEmPix parcela : vendas.parcelasPixLancadasEntre(FormaPagamento.PIX,
                StatusPagamento.PENDENTE, StatusPagamento.RECUSADO, umDia.inicioInclusivo(),
                umDia.fimExclusivo(), operadorId)) {
            lancamentos.add(Lancamento.de(OrigemDoLancamento.PARCELA, parcela));
        }
        for (LancamentoEmPix recebimento : vendas.recebimentosPixLancadosEntre(FormaPagamento.PIX,
                umDia.inicioInclusivo(), umDia.fimExclusivo(), operadorId)) {
            lancamentos.add(Lancamento.de(OrigemDoLancamento.RECEBIMENTO, recebimento));
        }
        // Cada consulta já vem em ordem; juntas, as duas listas se intercalam pela hora, e o id
        // desempata como nas consultas.
        lancamentos.sort(Comparator.comparing(Lancamento::lancadoEm)
                .thenComparing(Lancamento::id));
        return new ConferenciaDoPix(dia, lancamentos);
    }

    /**
     * A resposta do caso de uso, aninhada no serviço como a dos outros relatórios.
     *
     * @param dia         o dia do balcão consultado
     * @param lancamentos em ordem de lançamento; vazia quando não houve Pix, nunca nula
     */
    public record ConferenciaDoPix(LocalDate dia, List<Lancamento> lancamentos) {
    }

    /**
     * Um Pix da lista.
     *
     * @param id                    a parcela ou o recebimento
     * @param origem                parcela de venda ou recebimento de fiado
     * @param vendaId               a venda a que o Pix pertence
     * @param lancadoEm             quando foi lançado
     * @param valor                 o valor do Pix
     * @param operadorId            quem lançou; o nome é do módulo de contas, e a tela já o tem
     * @param situacaoDaVenda       a situação atual da venda
     * @param clienteId             o cliente da venda; nulo quando não há, e o nome é do cadastro
     * @param integrado             cobrança criada no provedor; falso no Pix lançado à mão
     * @param aguardandoConfirmacao cobrança integrada cujo pagamento o provedor ainda não confirmou
     */
    public record Lancamento(UUID id, OrigemDoLancamento origem, UUID vendaId, Instant lancadoEm,
            Money valor, UUID operadorId, StatusVenda situacaoDaVenda, UUID clienteId,
            boolean integrado, boolean aguardandoConfirmacao) {

        static Lancamento de(OrigemDoLancamento origem, LancamentoEmPix linha) {
            return new Lancamento(linha.id(), origem, linha.vendaId(), linha.lancadoEm(),
                    Money.de(linha.valor()), linha.usuarioId(), linha.situacaoDaVenda(),
                    linha.clienteId(), linha.integrado(), linha.pendente());
        }
    }
}
