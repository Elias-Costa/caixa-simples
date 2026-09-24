package br.com.caixasimples.pagamentos.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Dados da cobrança integrada; o código copia e cola só existe após resposta verificável. */
public record CobrancaPix(String txid, String chaveRecebedora, Instant expiraEm,
        String copiaECola, Estado estado) {

    public enum Estado { AGUARDANDO, DISPONIVEL, INCERTA }

    public CobrancaPix {
        Objects.requireNonNull(txid);
        Objects.requireNonNull(chaveRecebedora);
        Objects.requireNonNull(expiraEm);
        Objects.requireNonNull(estado);
        if (!txid.matches("[a-zA-Z0-9]{26,35}")) {
            throw new IllegalArgumentException("txid Pix invalido");
        }
        if (estado == Estado.DISPONIVEL && (copiaECola == null || copiaECola.isBlank())) {
            throw new IllegalArgumentException("cobranca disponivel exige Pix copia e cola");
        }
    }

    public static CobrancaPix aguardando(UUID pagamentoId, String chave, Instant expiraEm) {
        return new CobrancaPix(pagamentoId.toString().replace("-", ""), chave, expiraEm,
                null, Estado.AGUARDANDO);
    }

    public CobrancaPix disponivel(String codigo) {
        return new CobrancaPix(txid, chaveRecebedora, expiraEm, codigo, Estado.DISPONIVEL);
    }

    public CobrancaPix incerta() {
        return new CobrancaPix(txid, chaveRecebedora, expiraEm, copiaECola, Estado.INCERTA);
    }
}
