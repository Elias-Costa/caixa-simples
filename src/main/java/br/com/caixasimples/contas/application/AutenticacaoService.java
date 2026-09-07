package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import br.com.caixasimples.contas.internal.EmissorDeToken;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Caso de uso de login (RNF06).
 *
 * <p>Tres passos, nesta ordem, e a ordem importa: a credencial e o unico dado consultavel antes de
 * existir tenant (D9); e ela que revela a conta; so entao o usuario pode ser lido sob o filtro
 * normal de {@code @TenantId}.
 *
 * <p><strong>Toda falha responde igual.</strong> E-mail inexistente, senha errada e usuario
 * inativo produzem a mesma excecao com a mesma mensagem — distinguir permitiria descobrir quais
 * e-mails existem no sistema.
 */
@Service
public class AutenticacaoService {

    private final CredencialRepository credenciais;
    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final EmissorDeToken emissor;

    AutenticacaoService(CredencialRepository credenciais, UsuarioRepository usuarios,
            PasswordEncoder encoder, EmissorDeToken emissor) {
        this.credenciais = credenciais;
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.emissor = emissor;
    }

    /**
     * <strong>Sem {@code @Transactional} de proposito.</strong> Uma transacao unica abriria a
     * sessao do Hibernate <em>antes</em> de o tenant existir, e o tenant de uma sessao e resolvido
     * na abertura dela: a busca do usuario rodaria sob o sentinela e voltaria vazia, fazendo todo
     * login falhar. Cada consulta abrindo a propria sessao e o que permite a segunda enxergar a
     * conta que a primeira descobriu.
     *
     * <p>Nao ha perda: sao duas leituras que nao precisam ser atomicas entre si.
     *
     * @return o token de acesso, valido por 24h (A4)
     * @throws CredenciaisInvalidasException em qualquer falha
     */
    public String entrar(String email, String senha) {
        if (email == null || senha == null) {
            throw new CredenciaisInvalidasException();
        }

        Credencial credencial = credenciais.findByEmailIgnoreCase(email.trim())
                .orElseThrow(CredenciaisInvalidasException::new);

        if (!encoder.matches(senha, credencial.getSenhaHash())) {
            throw new CredenciaisInvalidasException();
        }

        ContaId conta = credencial.getContaId();

        // A partir daqui o tenant existe, e o usuario e lido sob o filtro automatico como qualquer
        // outro dado de conta.
        Usuario usuario = TenantContext.executarComo(conta,
                () -> usuarios.findById(credencial.getUsuarioId()))
                .filter(Usuario::isAtivo)
                .orElseThrow(CredenciaisInvalidasException::new);

        return emissor.emitir(usuario.getId(), conta, usuario.getPerfil());
    }
}
