package br.com.caixasimples.contas;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Monta conta, usuário e credencial num passo só, pelo mesmo caminho que a criação de conta usa.
 *
 * <p>Existe porque quase todo teste de integração precisa de uma conta com alguém que consiga
 * entrar, e montar isso à mão em cada teste esconderia o que o teste quer de fato provar.
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
        return new ContaCriada(contaId, usuarioId, email, senha);
    }

    /**
     * Mais um usuário dentro de uma conta que já existe: o segundo atendente do mesmo negócio.
     *
     * <p>Nasce <strong>sem credencial</strong>, de propósito. Quem precisa dele é teste de regra
     * por operador, como a de um caixa aberto por vez, e não teste de login. Acrescentar uma
     * credencial que ninguém usa só tornaria a fixture mais lenta e menos legível.
     */
    public UUID criarOperadorEm(ContaId contaId, String nome) {
        return TenantContext.executarComo(contaId, () ->
                usuarios.save(new Usuario(nome, Perfil.OPERADOR)).getId());
    }

    public record ContaCriada(ContaId contaId, UUID usuarioId, String email, String senha) {
    }
}
