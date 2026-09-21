package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import br.com.caixasimples.contas.internal.EmissorDeToken;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioAutenticado;
import br.com.caixasimples.shared.UsuarioContext;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de identidade: o login (RNF06) e quem está operando agora.
 *
 * <p>O login são três passos, nesta ordem, e a ordem importa: a credencial é o único dado
 * consultável antes de existir tenant; é ela que revela a conta; e só então o usuário pode ser
 * lido sob o filtro normal de {@code @TenantId}.
 *
 * <p><strong>Toda falha responde igual.</strong> E-mail inexistente, senha errada e usuário inativo
 * produzem a mesma exceção com a mesma mensagem, porque distinguir permitiria descobrir quais
 * e-mails existem no sistema.
 */
@Service
public class AutenticacaoService {

    private final CredencialRepository credenciais;
    private final UsuarioRepository usuarios;
    private final ContaRepository contas;
    private final PasswordEncoder encoder;
    private final EmissorDeToken emissor;

    AutenticacaoService(CredencialRepository credenciais, UsuarioRepository usuarios,
            ContaRepository contas, PasswordEncoder encoder, EmissorDeToken emissor) {
        this.credenciais = credenciais;
        this.usuarios = usuarios;
        this.contas = contas;
        this.encoder = encoder;
        this.emissor = emissor;
    }

    /**
     * <strong>Sem {@code @Transactional} de propósito.</strong> Uma transação única abriria a
     * sessão do Hibernate <em>antes</em> de o tenant existir, e o tenant de uma sessão é resolvido
     * na abertura dela: a busca do usuário rodaria sob o sentinela e voltaria vazia, fazendo todo
     * login falhar. Cada consulta abrindo a própria sessão é o que permite a segunda enxergar a
     * conta que a primeira descobriu.
     *
     * <p>Não há perda, porque são duas leituras que não precisam ser atômicas entre si.
     *
     * @return o token de acesso
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

        // A partir daqui o tenant existe, e o usuário é lido sob o filtro automático como qualquer
        // outro dado de conta.
        Usuario usuario = TenantContext.executarComo(conta,
                () -> usuarios.findById(credencial.getUsuarioId()))
                .filter(Usuario::isAtivo)
                .orElseThrow(CredenciaisInvalidasException::new);

        return emissor.emitir(usuario.getId(), conta, usuario.getPerfil());
    }

    /**
     * Quem está operando e em que negócio, para o cabeçalho de toda tela.
     *
     * <p>Não recebe id: a pessoa e a conta são as do contexto, postas ali pelo filtro do token,
     * e não há por onde perguntar por outra (RNF05). O perfil vem do contexto, que já o leu do
     * banco nesta requisição. O nome do negócio e o tipo dele saem daqui, e não do comprovante
     * ou de outro caso de uso, porque a tela mostra o negócio em toda página e os outros módulos
     * não precisam saber o nome: o tipo serve à oferta do catálogo inicial (RF32), e o
     * interruptor do estoque decide se o menu de estoque aparece (RF17).
     *
     * <p>Aqui a transação pode existir, ao contrário do login: o tenant já está no contexto
     * antes de ela abrir.
     *
     * @throws br.com.caixasimples.shared.UsuarioNaoResolvidoException se não há usuário no contexto
     * @throws br.com.caixasimples.shared.TenantNaoResolvidoException se não há conta no contexto
     * @throws IllegalStateException se o usuário ou a conta do contexto não existem no banco, o que
     *         o filtro do token já impede numa requisição normal
     */
    @Transactional(readOnly = true)
    public Identidade identidade() {
        UsuarioAutenticado atual = UsuarioContext.exigirAtual();
        ContaId contaId = TenantContext.exigirAtual();

        Usuario usuario = usuarios.findById(atual.usuarioId())
                .orElseThrow(() -> new IllegalStateException(
                        "usuario do contexto nao existe: " + atual.usuarioId()));
        Conta conta = contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));

        return new Identidade(usuario.getId(), usuario.getNome(), atual.perfil(),
                conta.getId(), conta.getNomeNegocio(), conta.getTipoNegocio(),
                conta.isEstoqueHabilitado());
    }

    /**
     * Quem está operando e em que negócio.
     *
     * @param tipoNegocio nulo quando a conta foi criada sem tipo, e então não há catálogo
     *                    inicial a oferecer
     */
    public record Identidade(UUID usuarioId, String nome, Perfil perfil, UUID contaId,
            String nomeNegocio, String tipoNegocio, boolean estoqueHabilitado) {
    }
}
