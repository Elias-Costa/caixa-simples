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
 * Monta conta, usuario e credencial num passo so, pelo mesmo caminho que o seed usa.
 *
 * <p>Existe porque quase todo teste daqui pra frente precisa de uma conta com alguem que consiga
 * logar, e montar isso a mao em cada teste esconderia o que o teste quer de fato provar.
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

    public record ContaCriada(ContaId contaId, UUID usuarioId, String email, String senha) {
    }
}
