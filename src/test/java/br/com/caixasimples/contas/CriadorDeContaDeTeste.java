package br.com.caixasimples.contas;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioAutenticado;
import br.com.caixasimples.shared.UsuarioContext;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Monta conta, usuário e credencial num passo só, pelo mesmo caminho que a criação de conta usa.
 *
 * <p>Existe porque quase todo teste de integração precisa de uma conta com alguém que consiga
 * entrar, e montar isso à mão em cada teste esconderia o que o teste quer de fato provar.
 *
 * <p>O que ela devolve sabe executar código <strong>como aquela pessoa</strong>: conta no
 * contexto de tenant e usuário no contexto de identidade, os dois juntos, que é o que o filtro do
 * token faz numa requisição de verdade. Todo caso de uso restrito por perfil precisa disso.
 */
public class CriadorDeContaDeTeste {

    private final ContaRepository contas;
    private final UsuarioRepository usuarios;
    private final CredencialRepository credenciais;
    private final PasswordEncoder encoder;

    public CriadorDeContaDeTeste(ContaRepository contas, UsuarioRepository usuarios,
            CredencialRepository credenciais, PasswordEncoder encoder) {
        this.contas = contas;
        this.usuarios = usuarios;
        this.credenciais = credenciais;
        this.encoder = encoder;
    }

    public ContaCriada criar(String nomeNegocio, String senha) {
        return criar(nomeNegocio, senha, Perfil.ADMIN, true);
    }

    public ContaCriada criar(String nomeNegocio, String senha, Perfil perfil, boolean ativo) {
        ContaId contaId = contas.save(new Conta(nomeNegocio, null, Plano.GRATIS)).contaId();
        String email = "teste-" + UUID.randomUUID() + "@exemplo.test";

        UUID usuarioId = TenantContext.executarComo(contaId, () -> {
            Usuario usuario = new Usuario("Pessoa de " + nomeNegocio, perfil);
            if (!ativo) {
                usuario.inativar();
            }
            return usuarios.save(usuario).getId();
        });

        credenciais.save(new Credencial(email, encoder.encode(senha), usuarioId, contaId));
        return new ContaCriada(contaId, usuarioId, perfil, email, senha);
    }

    /**
     * Mais um usuário dentro de uma conta que já existe: o segundo atendente do mesmo negócio.
     *
     * <p>Nasce <strong>sem credencial</strong>, de propósito. Quem precisa dele é teste de regra
     * por operador, como a de um caixa aberto por vez, e não teste de login. Acrescentar uma
     * credencial que ninguém usa só tornaria a fixture mais lenta e menos legível.
     */
    public UsuarioCriado criarOperadorEm(ContaId contaId, String nome) {
        return criarEm(contaId, nome, Perfil.OPERADOR);
    }

    /** Um segundo administrador na mesma conta, para os testes de gestão de usuários. */
    public UsuarioCriado criarAdminEm(ContaId contaId, String nome) {
        return criarEm(contaId, nome, Perfil.ADMIN);
    }

    private UsuarioCriado criarEm(ContaId contaId, String nome, Perfil perfil) {
        UUID usuarioId = TenantContext.executarComo(contaId, () ->
                usuarios.save(new Usuario(nome, perfil)).getId());
        return new UsuarioCriado(contaId, usuarioId, perfil);
    }

    /**
     * Liga o controle de estoque da conta (RF17). Conta nasce com ele desligado, então todo teste
     * da baixa por venda precisa deste passo; a tela de configuração que fará isso ainda não
     * existe.
     */
    public void habilitarEstoque(ContaId contaId) {
        Conta conta = contas.findById(contaId.valor()).orElseThrow();
        conta.definirEstoqueHabilitado(true);
        contas.save(conta);
    }

    /**
     * Troca o plano da conta (RF31). Conta nasce no plano grátis, e só o plano mais alto admite
     * mais de um usuário; o caso de uso de troca de plano ainda não existe.
     */
    public void trocarPlano(ContaId contaId, Plano plano) {
        Conta conta = contas.findById(contaId.valor()).orElseThrow();
        conta.trocarPlano(plano);
        contas.save(conta);
    }

    /**
     * Uma conta com a pessoa que a criou, que sabe executar código como ela.
     */
    public record ContaCriada(ContaId contaId, UUID usuarioId, Perfil perfil, String email,
            String senha) {

        /** Conta e usuário nos dois contextos, como numa requisição autenticada desta pessoa. */
        public <T> T comoUsuario(Supplier<T> acao) {
            return executarComo(contaId, usuarioId, perfil, acao);
        }

        public void comoUsuario(Runnable acao) {
            executarComo(contaId, usuarioId, perfil, acao);
        }
    }

    /**
     * Um usuário criado dentro de uma conta que já existia, que também sabe executar código como
     * ele.
     */
    public record UsuarioCriado(ContaId contaId, UUID usuarioId, Perfil perfil) {

        public <T> T comoUsuario(Supplier<T> acao) {
            return executarComo(contaId, usuarioId, perfil, acao);
        }

        public void comoUsuario(Runnable acao) {
            executarComo(contaId, usuarioId, perfil, acao);
        }
    }

    private static <T> T executarComo(ContaId contaId, UUID usuarioId, Perfil perfil,
            Supplier<T> acao) {
        return TenantContext.executarComo(contaId, () ->
                UsuarioContext.executarComo(new UsuarioAutenticado(usuarioId, perfil), acao));
    }

    private static void executarComo(ContaId contaId, UUID usuarioId, Perfil perfil,
            Runnable acao) {
        executarComo(contaId, usuarioId, perfil, () -> {
            acao.run();
            return null;
        });
    }
}
