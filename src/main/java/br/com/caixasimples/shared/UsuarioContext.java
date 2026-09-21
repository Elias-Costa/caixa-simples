package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Usuário da operação em curso, e as duas perguntas de autorização que os casos de uso fazem.
 *
 * <p>Preenchido pelo mesmo filtro que preenche {@link TenantContext}, a partir do token
 * autenticado e do banco, <strong>nunca</strong> a partir de corpo, path, query ou header da
 * requisição: perfil que viesse do cliente seria perfil que o cliente escolhe. Pelo mesmo motivo,
 * nenhum caso de uso recebe perfil por parâmetro; ele pergunta aqui.
 *
 * <p>A verificação é explícita, uma chamada na primeira linha de cada caso de uso restrito, em vez
 * de anotação com efeito por proxy: quem lê o caso de uso vê quem pode chamá-lo sem precisar
 * conhecer a configuração de segurança. Um ouvinte de evento roda sem usuário, e por isso só
 * chama caso de uso que não pergunta.
 *
 * <p>Mesma implementação de {@link TenantContext}, {@link ThreadLocal}, e pelos mesmos motivos;
 * são dois contextos, e não um, porque nascem em momentos diferentes da requisição e porque o do
 * tenant já é lido por todo o sistema e não precisava mudar.
 */
public final class UsuarioContext {

    private static final ThreadLocal<UsuarioAutenticado> ATUAL = new ThreadLocal<>();

    private UsuarioContext() {
    }

    public static void definir(UsuarioAutenticado usuario) {
        ATUAL.set(Objects.requireNonNull(usuario, "usuario nao pode ser nulo"));
    }

    public static Optional<UsuarioAutenticado> atual() {
        return Optional.ofNullable(ATUAL.get());
    }

    /**
     * @throws UsuarioNaoResolvidoException se não houver usuário no contexto. Falhar é o
     *         comportamento correto; um caso de uso restrito não roda para ninguém.
     */
    public static UsuarioAutenticado exigirAtual() {
        UsuarioAutenticado usuario = ATUAL.get();
        if (usuario == null) {
            throw new UsuarioNaoResolvidoException();
        }
        return usuario;
    }

    /**
     * Só o administrador passa (RF30).
     *
     * @throws UsuarioNaoResolvidoException se não há usuário no contexto
     * @throws AcessoNegadoException        se quem chama não é ADMIN
     */
    public static void exigirAdmin() {
        UsuarioAutenticado usuario = exigirAtual();
        if (!usuario.ehAdmin()) {
            throw new AcessoNegadoException("Esta operacao e do administrador da conta");
        }
    }

    /**
     * Passa quem é dono do que está sendo tocado, ou o administrador, que faz tudo em qualquer
     * caixa da conta. É a regra do <strong>próprio caixa</strong> do perfil Operador.
     *
     * <p>{@code dono} nulo nunca é ninguém: um operador que pede o histórico da conta inteira,
     * sem operador, cai aqui e é recusado, enquanto o administrador passa.
     *
     * @param dono o usuário a quem a sessão, a venda ou o histórico pertence
     * @throws UsuarioNaoResolvidoException se não há usuário no contexto
     * @throws AcessoNegadoException        se quem chama não é o dono nem ADMIN
     */
    public static void exigirDonoOuAdmin(UUID dono) {
        UsuarioAutenticado usuario = exigirAtual();
        if (usuario.ehAdmin()) {
            return;
        }
        if (dono == null || !dono.equals(usuario.usuarioId())) {
            throw new AcessoNegadoException(
                    "O operador so alcanca o proprio caixa e as proprias vendas");
        }
    }

    /**
     * Obrigatório ao fim de cada requisição: thread reaproveitada não pode herdar o usuário da
     * requisição anterior.
     */
    public static void limpar() {
        ATUAL.remove();
    }

    /**
     * Executa uma ação como um usuário específico, restaurando o anterior no fim. Use em teste.
     * Nunca use para tomar emprestado outro usuário dentro do fluxo de uma requisição.
     */
    public static <T> T executarComo(UsuarioAutenticado usuario, Supplier<T> acao) {
        UsuarioAutenticado anterior = ATUAL.get();
        definir(usuario);
        try {
            return acao.get();
        } finally {
            if (anterior == null) {
                limpar();
            } else {
                ATUAL.set(anterior);
            }
        }
    }

    public static void executarComo(UsuarioAutenticado usuario, Runnable acao) {
        executarComo(usuario, () -> {
            acao.run();
            return null;
        });
    }
}
