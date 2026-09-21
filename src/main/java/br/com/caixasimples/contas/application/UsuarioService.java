package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.PoliticaDeSenha;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestão de usuários da conta em operação (RF29): criar, listar e inativar.
 *
 * <p><strong>Só o administrador chama</strong>, e cada caso de uso pergunta isso na primeira
 * linha. A conta nunca chega por parâmetro: é a do contexto, como em {@link ContaService}, e o
 * usuário novo nasce nela pelo preenchimento automático do tenant.
 *
 * <p>Criar um usuário é criar duas linhas: o {@code Usuario}, que carrega perfil e tenant, e a
 * {@code Credencial}, que carrega e-mail e senha e é o que o login consulta. As duas nascem na
 * mesma transação, e aqui a transação pode envolver tudo, ao contrário do login: o tenant já está
 * no contexto antes de ela abrir.
 *
 * <p>A senha inicial passa pela mesma política de qualquer senha, inclusive a verificação contra
 * lista de senhas vazadas, que recusa se a verificação não puder ser feita. Criar usuário é raro e
 * quem cria é o dono, conectado; esperar e tentar de novo custa menos que uma senha vazada entrar.
 */
@Service
public class UsuarioService {

    private final ContaRepository contas;
    private final UsuarioRepository usuarios;
    private final CredencialRepository credenciais;
    private final PasswordEncoder encoder;
    private final PoliticaDeSenha politica;

    UsuarioService(ContaRepository contas, UsuarioRepository usuarios,
            CredencialRepository credenciais, PasswordEncoder encoder, PoliticaDeSenha politica) {
        this.contas = contas;
        this.usuarios = usuarios;
        this.credenciais = credenciais;
        this.encoder = encoder;
        this.politica = politica;
    }

    /**
     * Cria um usuário na conta em operação, com perfil e login (RF29).
     *
     * <p><strong>Consulta o e-mail antes de gravar</strong>, como a abertura de caixa faz com a
     * sessão aberta: e-mail repetido é rotina, e quem chama precisa de uma exceção que diga isso.
     * O índice único global sobre o e-mail continua existindo como rede, para duas requisições
     * simultâneas que passem juntas por esta checagem.
     *
     * @return o id do usuário criado, gerado na aplicação
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws PlanoSemMultiusuarioException se o plano da conta só admite um usuário
     * @throws br.com.caixasimples.contas.internal.SenhaRecusadaException se a senha é curta,
     *         vazada ou não pôde ser verificada
     * @throws EmailJaCadastradoException se o e-mail já tem login, nesta ou em outra conta
     * @throws IllegalArgumentException se nome ou e-mail estão em branco
     */
    @Transactional
    public UUID criar(String nome, Perfil perfil, String email, String senha) {
        UsuarioContext.exigirAdmin();
        Objects.requireNonNull(perfil, "perfil nao pode ser nulo");
        String emailNormalizado = exigirTexto(email, "email");

        Conta conta = contaDoContexto();
        if (!conta.getPlano().permiteMultiusuario()) {
            throw new PlanoSemMultiusuarioException(conta.getPlano());
        }

        // Falha antes de gravar qualquer coisa se a senha não servir.
        politica.exigirValida(senha);

        if (credenciais.existsByEmailIgnoreCase(emailNormalizado)) {
            throw new EmailJaCadastradoException(emailNormalizado);
        }

        Usuario usuario = usuarios.save(new Usuario(nome, perfil));
        credenciais.save(new Credencial(emailNormalizado, encoder.encode(senha), usuario.getId(),
                conta.contaId()));
        return usuario.getId();
    }

    /**
     * Todos os usuários da conta, ativos e inativos, para a tela de gestão.
     *
     * <p>Sem o e-mail, de propósito: ele mora na credencial, a tabela consultada antes de existir
     * tenant, e listá-lo aqui exigiria uma segunda consulta sem filtro de tenant naquela tabela.
     * O nome e o perfil bastam para administrar; o e-mail é dado de login.
     *
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     */
    @Transactional(readOnly = true)
    public List<UsuarioDaConta> listar() {
        UsuarioContext.exigirAdmin();
        return usuarios.findAll().stream()
                .map(usuario -> new UsuarioDaConta(usuario.getId(), usuario.getNome(),
                        usuario.getPerfil(), usuario.isAtivo()))
                .toList();
    }

    /**
     * Soft delete: o usuário deixa de entrar na requisição seguinte e o histórico de vendas e de
     * caixas dele fica intacto. Um administrador pode inativar a si mesmo, desde que não seja o
     * último; a conta não pode ficar sem ninguém que a administre.
     *
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws UsuarioNaoEncontradoException se o id não existe nesta conta
     * @throws UltimoAdministradorException se é o último administrador ativo
     */
    @Transactional
    public void inativar(UUID usuarioId) {
        UsuarioContext.exigirAdmin();
        Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");

        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new UsuarioNaoEncontradoException(usuarioId));

        if (usuario.isAtivo() && usuario.getPerfil() == Perfil.ADMIN
                && usuarios.countByPerfilAndAtivoTrue(Perfil.ADMIN) <= 1) {
            throw new UltimoAdministradorException(usuarioId);
        }

        usuario.inativar();
        usuarios.save(usuario);
    }

    private Conta contaDoContexto() {
        ContaId contaId = TenantContext.exigirAtual();
        return contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
    }

    private static String exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(campo + " nao pode ser vazio");
        }
        return valor.trim();
    }

    /**
     * Uma linha da lista de usuários. Aninhado no serviço porque é o formato de resposta deste
     * caso de uso e de mais nenhum; não é a entidade, que fica dentro do módulo.
     */
    public record UsuarioDaConta(UUID id, String nome, Perfil perfil, boolean ativo) {
    }
}
