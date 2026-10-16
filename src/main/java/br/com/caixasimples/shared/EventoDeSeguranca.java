package br.com.caixasimples.shared;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uma linha de evento de segurança no log: o login, a recusa de acesso, a revogação e a ação do
 * administrador sobre a Conta.
 *
 * <p>A linha é uma só, no formato {@code evento=login.recusado conta=... motivo=senha origem=...},
 * com o nome fixo do evento primeiro. É o que se procura no log para responder qual Conta foi
 * atacada, de onde e por quem foi feita uma ação, e é o que o alerta de rajada conta. Leva ids, a
 * origem e o motivo numa categoria fixa, nunca nome, e-mail, contato, senha nem token: o id só se
 * liga a uma pessoa pelo banco, e a categoria no lugar da mensagem impede que o texto de uma exceção
 * leve dado pessoal ao log.
 *
 * <p>Todo valor passa pela mesma limpeza: o caractere fora de letras sem acento, dígitos e
 * {@code ._:/@%+-} vira sublinhado, e o valor para em 200 caracteres. O caminho e a origem vêm do
 * cliente, e um espaço ou uma quebra de linha neles bastariam para forjar outro campo ou outro
 * evento.
 *
 * <p>Não depende de servlet: quem registra está na borda HTTP e passa a origem que o servidor já leu
 * da cadeia de proxies confiáveis.
 */
public final class EventoDeSeguranca {

    private static final Logger log = LoggerFactory.getLogger(EventoDeSeguranca.class);

    private static final int TAMANHO_MAXIMO = 200;

    private static final Pattern FORA_DO_PERMITIDO = Pattern.compile("[^A-Za-z0-9._:/@%+-]");

    private final StringBuilder linha;

    private EventoDeSeguranca(String nome) {
        this.linha = new StringBuilder("evento=").append(limpo(nome));
    }

    public static EventoDeSeguranca de(String nome) {
        return new EventoDeSeguranca(nome);
    }

    /** O valor nulo deixa o campo de fora, para a linha não dizer que algo existe sem existir. */
    public EventoDeSeguranca com(String campo, Object valor) {
        if (valor != null) {
            linha.append(' ').append(limpo(campo)).append('=').append(limpo(valor.toString()));
        }
        return this;
    }

    /** @param ip o endereço que o servidor leu da cadeia de proxies confiáveis */
    public EventoDeSeguranca daOrigem(String ip) {
        return com("origem", ip);
    }

    /** A Conta e o Usuário da requisição, quando já estão nos contextos. */
    public EventoDeSeguranca doContexto() {
        TenantContext.atual().ifPresent(conta -> com("conta", conta));
        UsuarioContext.atual().ifPresent(usuario -> com("usuario", usuario.usuarioId()));
        return this;
    }

    /** O evento que é a operação seguindo o caminho normal, como o login certo. */
    public void registrar() {
        log.info("{}", linha);
    }

    /** O evento que é uma recusa, como o login errado ou o token que não vale mais. */
    public void registrarRecusa() {
        log.warn("{}", linha);
    }

    private static String limpo(String valor) {
        String cortado = valor.length() > TAMANHO_MAXIMO
                ? valor.substring(0, TAMANHO_MAXIMO)
                : valor;
        return FORA_DO_PERMITIDO.matcher(cortado).replaceAll("_");
    }
}
