package br.com.caixasimples.contas.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Contenção de abuso no login (RNF06): quantas falhas cada origem, e cada e-mail vindo dela, ainda
 * pode ter antes de esperar.
 *
 * <p>São duas contagens, cada uma com a própria janela de 15 minutos, que começa na primeira falha
 * e zera ao fim dela. A do par de origem e e-mail aceita 10 falhas: é a apertada, e quem tenta de
 * outra origem não a alcança, então ninguém de fora bloqueia o Usuário legítimo. A da origem soma
 * todos os e-mails, inclusive os que não existem, e aceita 30: segura quem troca de e-mail para
 * escapar do par, com folga para vários operadores atrás do mesmo IP. Esgotada qualquer uma, a
 * tentativa é recusada até a janela dela acabar, mesmo com a senha certa, que de outro modo
 * continuaria descobrível.
 *
 * <p>A tentativa é contada ao entrar, antes do banco e do BCrypt, e devolvida se não terminar em
 * recusa de credencial. Conferir o limite na entrada e contar a falha só depois da conferência da
 * senha deixaria pedidos simultâneos passarem todos antes de algum ser contado. Por isso a
 * conferência das duas contagens e a reserva são um passo só, sob a trava deste objeto, curta e
 * sem nenhuma espera dentro dela.
 *
 * <p>A contagem fica na memória porque a aplicação roda numa instância só: um reinício a zera, o que
 * dá a quem insiste no máximo uma janela a mais, e nem IP nem e-mail são gravados. A chave do par
 * guarda um resumo do e-mail, e o aviso de bloqueio no log não leva nenhum dos dois.
 */
@Component
public class ContencaoDeLogin {

    static final int FALHAS_POR_PAR = 10;
    static final int FALHAS_POR_ORIGEM = 30;
    static final Duration JANELA = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(ContencaoDeLogin.class);

    /**
     * Texto que só pode ser um IPv6, abreviado ou não, com ou sem um IPv4 no fim. Nessa forma o JDK
     * só lê o endereço literal e recusa o inválido sem consultar DNS.
     */
    private static final Pattern FORMA_DE_IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");

    private final Map<String, Contagem> porPar = new HashMap<>();
    private final Map<String, Contagem> porOrigem = new HashMap<>();
    private Instant ultimaLimpeza = Instant.MIN;

    /**
     * Conta uma tentativa de login antes de ela consultar o banco.
     *
     * @param origem o IP de quem pede, já lido da cadeia de proxies confiáveis
     * @return a tentativa, a devolver com {@link #registrarSucesso} ou {@link #desfazer} quando não
     *         terminar em recusa de credencial
     * @throws LoginContidoException se o par ou a origem esgotou a janela atual
     */
    public Tentativa reservar(String origem, String email, Instant agora) {
        String chaveDaOrigem = agruparOrigem(origem);
        String chaveDoPar = chaveDaOrigem + " " + resumir(email);

        Duration espera = Duration.ZERO;
        Instant avisoDoPar = null;
        Instant avisoDaOrigem = null;
        synchronized (this) {
            limparVencidas(agora);
            Contagem doPar = vigente(porPar.get(chaveDoPar), agora);
            Contagem daOrigem = vigente(porOrigem.get(chaveDaOrigem), agora);
            boolean parEsgotado = doPar.falhas() >= FALHAS_POR_PAR;
            boolean origemEsgotada = daOrigem.falhas() >= FALHAS_POR_ORIGEM;

            if (!parEsgotado && !origemEsgotada) {
                porPar.put(chaveDoPar, doPar.maisUma());
                porOrigem.put(chaveDaOrigem, daOrigem.maisUma());
                return new Tentativa(chaveDoPar, doPar.inicio(), chaveDaOrigem, daOrigem.inicio());
            }

            // A espera é a da contagem esgotada que termina por último, porque antes dela a
            // tentativa seria recusada de novo.
            if (parEsgotado) {
                espera = maior(espera, Duration.between(agora, doPar.fim()));
                if (!doPar.avisada()) {
                    porPar.put(chaveDoPar, doPar.avisando());
                    avisoDoPar = doPar.fim();
                }
            }
            if (origemEsgotada) {
                espera = maior(espera, Duration.between(agora, daOrigem.fim()));
                if (!daOrigem.avisada()) {
                    porOrigem.put(chaveDaOrigem, daOrigem.avisando());
                    avisoDaOrigem = daOrigem.fim();
                }
            }
        }

        // Fora da trava, porque escrever o log pode esperar pela saída.
        if (avisoDoPar != null) {
            log.warn("login contido ate {}: {} falhas do mesmo e-mail na mesma origem em {} minutos",
                    avisoDoPar, FALHAS_POR_PAR, JANELA.toMinutes());
        }
        if (avisoDaOrigem != null) {
            log.warn("login contido ate {}: {} falhas da mesma origem em {} minutos",
                    avisoDaOrigem, FALHAS_POR_ORIGEM, JANELA.toMinutes());
        }
        throw new LoginContidoException(espera);
    }

    /**
     * O login deu certo: a contagem do par zera, porque quem acertou a senha não precisa mais de
     * contenção nela, e a origem recebe a reserva de volta, porque só a falha conta.
     */
    public synchronized void registrarSucesso(Tentativa tentativa) {
        porPar.remove(tentativa.chaveDoPar());
        devolver(porOrigem, tentativa.chaveDaOrigem(), tentativa.inicioDaOrigem());
    }

    /**
     * A tentativa terminou sem recusa de credencial, num erro como o banco fora do ar: as duas
     * contagens recebem a reserva de volta, para uma queda do banco não deixar todo mundo
     * bloqueado quando ele volta.
     */
    public synchronized void desfazer(Tentativa tentativa) {
        devolver(porPar, tentativa.chaveDoPar(), tentativa.inicioDoPar());
        devolver(porOrigem, tentativa.chaveDaOrigem(), tentativa.inicioDaOrigem());
    }

    /**
     * A chave da origem: o IPv4 inteiro, e o IPv6 pelo prefixo /64, porque um assinante recebe ao
     * menos um /64 e troca de endereço dentro dele à vontade.
     *
     * <p>Só texto com forma de IPv6 é lido como endereço. Qualquer outro, como o que um cliente
     * escreve no cabeçalho do proxy quando não há proxy na frente, vale como chegou.
     */
    static String agruparOrigem(String origem) {
        if (origem.indexOf(':') < 0 || !FORMA_DE_IPV6.matcher(origem).matches()) {
            return origem;
        }
        InetAddress endereco;
        try {
            endereco = InetAddress.getByName(origem);
        } catch (UnknownHostException invalido) {
            return origem;
        }
        byte[] bytes = endereco.getAddress();
        if (bytes.length == 4) {
            // O IPv6 que só embute um IPv4 o JDK já devolve como o próprio IPv4.
            return endereco.getHostAddress();
        }
        byte[] prefixo = new byte[16];
        System.arraycopy(bytes, 0, prefixo, 0, 8);
        try {
            return InetAddress.getByAddress(prefixo).getHostAddress() + "/64";
        } catch (UnknownHostException impossivel) {
            // Só acontece com tamanho diferente de 4 ou 16 bytes.
            throw new IllegalStateException(impossivel);
        }
    }

    /** Quantas contagens estão guardadas, para o teste conferir a limpeza. */
    synchronized int contagensGuardadas() {
        return porPar.size() + porOrigem.size();
    }

    /**
     * O e-mail entra na chave do par como resumo SHA-256, sem espaços nas pontas e sem diferença
     * entre maiúscula e minúscula, como a busca da credencial o compara. O resumo tem tamanho fixo,
     * mesmo para um e-mail enorme no corpo do pedido, e não deixa o e-mail na memória.
     */
    private static String resumir(String email) {
        String normalizado = email.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] resumo = MessageDigest.getInstance("SHA-256")
                    .digest(normalizado.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumo);
        } catch (NoSuchAlgorithmException impossivel) {
            // Toda JVM é obrigada a oferecer SHA-256.
            throw new IllegalStateException(impossivel);
        }
    }

    /**
     * Uma vez por janela, tira da memória as contagens vencidas que ninguém voltou a tocar. Sem
     * isso, cada origem que já errou uma vez ficaria guardada até o próximo reinício.
     */
    private void limparVencidas(Instant agora) {
        if (agora.isBefore(ultimaLimpeza.plus(JANELA))) {
            return;
        }
        porPar.values().removeIf(contagem -> contagem.vencida(agora));
        porOrigem.values().removeIf(contagem -> contagem.vencida(agora));
        ultimaLimpeza = agora;
    }

    private static Contagem vigente(Contagem guardada, Instant agora) {
        if (guardada == null || guardada.vencida(agora)) {
            return new Contagem(agora, 0, false);
        }
        return guardada;
    }

    /**
     * Desconta a reserva só da janela em que ela foi contada: de uma janela aberta depois, ela não
     * fez parte. A contagem que volta a zero sai da memória, e a próxima falha abre uma janela nova.
     */
    private static void devolver(Map<String, Contagem> contagens, String chave, Instant inicio) {
        Contagem guardada = contagens.get(chave);
        if (guardada == null || !guardada.inicio().equals(inicio)) {
            return;
        }
        if (guardada.falhas() <= 1) {
            contagens.remove(chave);
        } else {
            contagens.put(chave, guardada.menosUma());
        }
    }

    private static Duration maior(Duration uma, Duration outra) {
        return uma.compareTo(outra) >= 0 ? uma : outra;
    }

    /**
     * Uma tentativa já contada, com o início da janela em que entrou em cada contagem.
     */
    public record Tentativa(String chaveDoPar, Instant inicioDoPar, String chaveDaOrigem,
            Instant inicioDaOrigem) {
    }

    /** Falhas contadas desde o início da janela, e se o bloqueio dela já foi avisado no log. */
    private record Contagem(Instant inicio, int falhas, boolean avisada) {

        Instant fim() {
            return inicio.plus(JANELA);
        }

        boolean vencida(Instant agora) {
            return !agora.isBefore(fim());
        }

        Contagem maisUma() {
            return new Contagem(inicio, falhas + 1, avisada);
        }

        Contagem menosUma() {
            return new Contagem(inicio, falhas - 1, avisada);
        }

        Contagem avisando() {
            return new Contagem(inicio, falhas, true);
        }
    }
}
