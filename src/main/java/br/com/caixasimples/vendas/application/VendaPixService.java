package br.com.caixasimples.vendas.application;

import br.com.caixasimples.pagamentos.application.PixCobrancaService;
import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.ConsultaPix;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.domain.Pagamento;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Persiste a reserva antes da chamada externa para que falha de rede preserve a tentativa. */
@Service
public class VendaPixService {
    private final VendaService vendas;
    private final PixCobrancaService pix;

    VendaPixService(VendaService vendas, PixCobrancaService pix) {
        this.vendas = vendas;
        this.pix = pix;
    }

    public Pagamento cobrar(UUID vendaId, UUID tentativaId, Money valor) {
        vendas.verificarAcesso(vendaId);
        // A chave e as credenciais precisam estar configuradas antes de reservar a parcela.
        Pagamento parcela = vendas.reservarPix(vendaId, tentativaId, valor, pix.chaveRecebedora());
        if (parcela.status() != StatusPagamento.PENDENTE) {
            return parcela;
        }
        CobrancaPix cobranca = parcela.cobrancaPix();
        if (cobranca.estado() == CobrancaPix.Estado.DISPONIVEL
                && Instant.now().isBefore(cobranca.expiraEm())) {
            return parcela;
        }
        if (!Instant.now().isBefore(cobranca.expiraEm())) {
            return vendas.atualizarCobrancaPix(vendaId, tentativaId, cobranca.incerta());
        }
        try {
            String codigo = pix.criarCobranca(cobranca, valor);
            return vendas.atualizarCobrancaPix(vendaId, tentativaId,
                    cobranca.disponivel(codigo));
        } catch (PixIndisponivelException erro) {
            return vendas.atualizarCobrancaPix(vendaId, tentativaId, cobranca.incerta());
        }
    }

    /** O txid do aviso só localiza uma parcela da Conta; o estado vem da reconsulta. */
    public void receberNotificacao(ContaId contaId, String txid) {
        TenantContext.executarComo(contaId, () -> {
            vendas.pixPorTxid(txid).ifPresent(alvo -> {
                Pagamento parcela = alvo.parcela();
                if (parcela.status() == StatusPagamento.CONFIRMADO) {
                    return;
                }
                ConsultaPix consulta = pix.consultar(parcela.cobrancaPix());
                if (!consulta.txid().equals(txid)
                        || !consulta.chaveRecebedora().equals(parcela.cobrancaPix().chaveRecebedora())
                        || !consulta.valor().equals(parcela.valor())) {
                    throw new PixIndisponivelException("consulta Pix diverge da parcela reservada");
                }
                if (consulta.pago()) {
                    vendas.confirmarPix(alvo.vendaId(), parcela.id(), txid,
                            consulta.valor(), consulta.chaveRecebedora());
                }
            });
        });
    }
}
