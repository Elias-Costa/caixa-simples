package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.SituacaoDoPlano;
import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.ContencaoDeLogin;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import br.com.caixasimples.contas.internal.EmissorDeToken;
import br.com.caixasimples.contas.internal.LoginContidoException;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioAutenticado;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de identidade: o login (RNF06) e quem está operando agora.
 *
 * <p>O login descobre a conta pela credencial, a única busca possível sem tenant, e só então lê o
 * usuário sob o filtro normal de {@code @TenantId}. No primeiro login de ADMIN, marca a Conta e
 * pede a cópia do catálogo inicial antes de emitir o token.
 *
 * <p><strong>Toda falha responde igual e custa igual.</strong> E-mail inexistente, senha errada e
 * usuário inativo produzem a mesma exceção com a mesma mensagem, depois de uma conferência de senha
 * cada um, porque distinguir, pela resposta ou pelo tempo dela, permitiria descobrir quais e-mails
 * existem no sistema.
 */
@Service
public class AutenticacaoService {

    private final CredencialRepository credenciais;
    private final UsuarioRepository usuarios;
    private final ContaRepository contas;
    private final PasswordEncoder encoder;
    private final EmissorDeToken emissor;
    private final ContaService contaService;
    private final ContencaoDeLogin contencao;

    /**
     * Conferido no lugar da senha guardada quando o e-mail não tem credencial. Nasce do mesmo
     * codificador que grava as senhas, então custa o mesmo BCrypt que elas, e não é a senha de
     * ninguém: o texto codificado é aleatório e não fica guardado.
     */
    private final String hashSintetico;

    AutenticacaoService(CredencialRepository credenciais, UsuarioRepository usuarios,
            ContaRepository contas, PasswordEncoder encoder, EmissorDeToken emissor,
            ContaService contaService, ContencaoDeLogin contencao) {
        this.credenciais = credenciais;
        this.usuarios = usuarios;
        this.contas = contas;
        this.encoder = encoder;
        this.emissor = emissor;
        this.contaService = contaService;
        this.contencao = contencao;
        this.hashSintetico = encoder.encode(UUID.randomUUID().toString());
    }

    /**
     * <strong>Sem {@code @Transactional} de propósito.</strong> Uma transação única abriria a
     * sessão do Hibernate <em>antes</em> de o tenant existir, e o tenant de uma sessão é resolvido
     * na abertura dela: a busca do usuário rodaria sob o sentinela e voltaria vazia, fazendo todo
     * login falhar. Cada consulta abrindo a própria sessão é o que permite a segunda enxergar a
     * conta que a primeira descobriu.
     *
     * <p>As leituras não precisam ser atômicas entre si. A escrita do primeiro acesso abre sua
     * própria transação depois de os contextos da Conta e do administrador terem sido definidos.
     *
     * <p>Antes de qualquer consulta, a tentativa é contada pela origem e pelo e-mail vindo dela, e
     * a que passou do limite é recusada sem tocar no banco. Só a recusa de credencial fica
     * contada: o login certo e o erro de outra natureza devolvem a reserva.
     *
     * @param origem o IP de quem pede, já lido da cadeia de proxies confiáveis
     * @return o token de acesso
     * @throws CredenciaisInvalidasException em qualquer falha de credencial
     * @throws LoginContidoException se a origem, ou o e-mail vindo dela, errou demais na janela
     *         atual
     */
    public String entrar(String email, String senha, String origem) {
        if (email == null || senha == null) {
            throw new CredenciaisInvalidasException();
        }

        ContencaoDeLogin.Tentativa tentativa = contencao.reservar(origem, email, Instant.now());
        String token;
        try {
            token = autenticar(email, senha);
        } catch (CredenciaisInvalidasException recusa) {
            // A reserva fica: é a falha que a contenção conta.
            throw recusa;
        } catch (RuntimeException erro) {
            // Um erro que não diz nada sobre a senha, como o banco fora do ar, não conta como falha.
            contencao.desfazer(tentativa);
            throw erro;
        }
        contencao.registrarSucesso(tentativa);
        return token;
    }

    private String autenticar(String email, String senha) {
        Optional<Credencial> encontrada = credenciais.findByEmailIgnoreCase(email.trim());
        if (encontrada.isEmpty()) {
            // Confere a senha mesmo assim, contra o hash sintético: recusar sem o BCrypt responderia
            // mais rápido que a senha errada, e o tempo diria quais e-mails existem.
            encoder.matches(senha, hashSintetico);
            throw new CredenciaisInvalidasException();
        }
        Credencial credencial = encontrada.get();

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

        if (usuario.getPerfil() == Perfil.ADMIN) {
            TenantContext.executarComo(conta, () -> UsuarioContext.executarComo(
                    new UsuarioAutenticado(usuario.getId(), usuario.getPerfil()),
                    contaService::registrarPrimeiroAcessoSeNecessario));
        }

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
     * <p>O plano vem junto porque o menu esconde o que ele não inclui ou o que está suspenso, e o
     * administrador vê o aviso de vencimento ao abrir o aplicativo, sem depender de outra tela.
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

        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);
        return new Identidade(usuario.getId(), usuario.getNome(), atual.perfil(),
                conta.getId(), conta.getNomeNegocio(), conta.getTipoNegocio(),
                conta.isEstoqueHabilitado(), conta.isNsuObrigatorio(), conta.getPlano(),
                conta.situacao(hoje),
                conta.getProximoVencimento(), conta.inicioDaSuspensao(), conta.recursos(hoje));
    }

    /**
     * Quem está operando e em que negócio.
     *
     * @param tipoNegocio       nulo quando a conta foi criada sem tipo, e então não há catálogo
     *                          inicial a oferecer
     * @param vencimentoDoPlano nulo no plano gratuito
     * @param inicioDaSuspensao o primeiro dia sem os recursos pagos se não houver renovação; nulo
     *                          no plano gratuito
     * @param nsuObrigatorio    se o pagamento em cartão exige o NSU; o aparelho guarda o valor
     *                          para recusar a parcela sem ele antes de gravá-la sem rede
     * @param recursos          os recursos do plano que valem hoje
     */
    public record Identidade(UUID usuarioId, String nome, Perfil perfil, UUID contaId,
            String nomeNegocio, String tipoNegocio, boolean estoqueHabilitado,
            boolean nsuObrigatorio, Plano plano,
            SituacaoDoPlano situacaoDoPlano, LocalDate vencimentoDoPlano,
            LocalDate inicioDaSuspensao, Set<RecursoDoPlano> recursos) {
    }
}
