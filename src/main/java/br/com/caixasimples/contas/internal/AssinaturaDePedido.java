package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Plano;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * O código que ativa um pedido de plano: a assinatura HMAC-SHA256 do id do pedido e do plano, com
 * um segredo que só a aplicação e o script de operação do mantenedor conhecem.
 *
 * <p>O código não é gravado. O mantenedor o gera fora da aplicação depois de conferir o Pix, e a
 * aplicação o recalcula para conferir, então quem lê o banco não encontra código válido nenhum, e
 * o mantenedor não precisa de acesso à Conta para ativá-la. Preso ao id do pedido, o código vale
 * para uma Conta, um plano e um período só.
 *
 * <p>Os primeiros 80 bits da assinatura, em base32 de Crockford, dão 16 caracteres sem as letras
 * que se confundem com algarismos, mostrados em quatro grupos de quatro para serem ditados ou
 * digitados. Adivinhar um código exigiria, em média, 2 elevado a 79 tentativas.
 */
public class AssinaturaDePedido {

    /** HMAC-SHA256 exige chave de ao menos 256 bits, a mesma exigência da chave do token. */
    private static final int MINIMO_DE_BYTES = 32;

    /** 80 bits: 16 caracteres de 5 bits cada. */
    private static final int BYTES_DO_CODIGO = 10;

    private static final String ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final SecretKeySpec chave;

    public AssinaturaDePedido(String segredo) {
        Objects.requireNonNull(segredo, "segredo nao pode ser nulo");
        byte[] bytes = segredo.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMO_DE_BYTES) {
            throw new IllegalStateException(
                    "CAIXA_SIMPLES_PLANO_SECRET precisa de ao menos " + MINIMO_DE_BYTES
                            + " bytes para HMAC-SHA256; recebeu " + bytes.length);
        }
        this.chave = new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** O código do pedido, em quatro grupos de quatro caracteres separados por hífen. */
    public String codigo(UUID pedidoId, Plano plano) {
        String semGrupos = semGrupos(pedidoId, plano);
        return semGrupos.substring(0, 4) + "-" + semGrupos.substring(4, 8) + "-"
                + semGrupos.substring(8, 12) + "-" + semGrupos.substring(12, 16);
    }

    /**
     * Se o código informado é o deste pedido e deste plano.
     *
     * <p>Ignora maiúsculas, espaços e hífens, que mudam com a forma de copiar ou ditar, e compara
     * em tempo constante, para que a demora da resposta não diga quantos caracteres acertaram.
     */
    public boolean confere(UUID pedidoId, Plano plano, String informado) {
        if (informado == null) {
            return false;
        }
        String normalizado = informado.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return MessageDigest.isEqual(
                semGrupos(pedidoId, plano).getBytes(StandardCharsets.US_ASCII),
                normalizado.getBytes(StandardCharsets.UTF_8));
    }

    private String semGrupos(UUID pedidoId, Plano plano) {
        Objects.requireNonNull(pedidoId, "pedidoId nao pode ser nulo");
        Objects.requireNonNull(plano, "plano nao pode ser nulo");
        String mensagem = "pedido-de-plano|" + pedidoId + "|" + plano.name();
        byte[] assinatura;
        try {
            // Mac não é seguro entre threads: uma instância por cálculo.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(chave);
            assinatura = mac.doFinal(mensagem.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponivel nesta JVM", e);
        }
        return base32(Arrays.copyOf(assinatura, BYTES_DO_CODIGO));
    }

    /** Cada 5 bits, do mais significativo ao menos, viram um caractere do alfabeto de Crockford. */
    private static String base32(byte[] bytes) {
        StringBuilder texto = new StringBuilder(bytes.length * 8 / 5);
        int acumulado = 0;
        int bits = 0;
        for (byte b : bytes) {
            acumulado = (acumulado << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                texto.append(ALFABETO.charAt((acumulado >>> bits) & 0x1F));
            }
            acumulado &= (1 << bits) - 1;
        }
        return texto.toString();
    }
}
