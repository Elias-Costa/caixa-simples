package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.shared.Money;

/** Porta do provedor Pix; a Conta é sempre a do contexto autenticado. */
public interface PixGateway {

    /**
     * Se o provedor da Conta está configurado, sem chamar o provedor: responde pela configuração,
     * e não pela disponibilidade dele agora.
     */
    boolean configurada();

    String chaveRecebedora();

    /** Repetir o mesmo txid não cria outra cobrança. */
    String criarCobranca(CobrancaPix cobranca, Money valor);

    /** Consulta o PSP; o aviso do webhook nunca decide o estado financeiro. */
    ConsultaPix consultar(CobrancaPix cobranca);

    /** Solicita a remoção; só a reconsulta comprova que a cobrança não pode mais ser paga. */
    void removerCobranca(CobrancaPix cobranca);
}
