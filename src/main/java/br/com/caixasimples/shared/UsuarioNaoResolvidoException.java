package br.com.caixasimples.shared;

/**
 * Lançada quando um caso de uso que pergunta quem chama roda sem usuário no contexto.
 *
 * <p>Falhar aqui é deliberado: a alternativa seria deixar passar quem não se identificou, que é
 * exatamente o que a autorização por perfil existe para impedir (RF30). Um ouvinte de evento,
 * que roda sem usuário, não deve chamar caso de uso restrito; se chamou, esta exceção denuncia.
 */
public class UsuarioNaoResolvidoException extends RuntimeException {

    public UsuarioNaoResolvidoException() {
        super("Nenhum usuario no contexto atual: a requisicao nao passou pelo filtro de "
                + "identidade ou a operacao foi acionada sem uma pessoa por tras");
    }
}
