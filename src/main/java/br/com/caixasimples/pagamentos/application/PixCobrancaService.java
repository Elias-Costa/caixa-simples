package br.com.caixasimples.pagamentos.application;

import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.shared.Money;
import org.springframework.stereotype.Service;

/** API pública de pagamentos para uma tentativa Pix já identificada pela Venda. */
@Service
public class PixCobrancaService {
    private final PixGateway gateway;

    public PixCobrancaService(PixGateway gateway) {
        this.gateway = gateway;
    }

    public String chaveRecebedora() {
        return gateway.chaveRecebedora();
    }

    public String criarCobranca(CobrancaPix cobranca, Money valor) {
        return gateway.criarCobranca(cobranca, valor);
    }
}
