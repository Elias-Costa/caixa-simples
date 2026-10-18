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
import br.com.caixasimples.contas.internal.PoliticaDeSenha;
import br.com.caixasimples.contas.internal.SenhaRecusadaException;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.EventoDeSeguranca;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Casos de uso de identidade (RNF06): o login, quem está operando agora, os próprios dados, a troca
 * da própria senha e do próprio e-mail e a saída de todos os aparelhos.
 *
 * <p>O login descobre a conta pela credencial, a única busca possível sem tenant, e só então lê o
 * usuário sob o filtro normal de {@code @TenantId}. No primeiro login de ADMIN, marca a Conta e
 * pede a cópia do catálogo inicial antes de emitir o token.
 *
 * <p><strong>Toda falha responde igual e custa igual.</strong> E-mail inexistente, senha errada e
 * usuário inativo produzem a mesma exceção com a mesma mensagem, depois de uma conferência de senha
 * cada um, porque distinguir, pela resposta ou pelo tempo dela, permitiria descobrir quais e-mails
 * existem no sistema. Só o log distingue: o login certo e o recusado vão a ele como evento de
 * segurança, a recusa com o motivo e, quando o e-mail existe, com a Conta e o Usuário da credencial,
 * para mostrar qual Conta está sendo atacada. Nunca com o e-mail.
 *
 * <p>A troca de senha e a saída de todos os aparelhos sobem a versão de acesso do usuário, que vai
 * dentro do token: todo token emitido antes recebe 401 na requisição seguinte. É a resposta à senha
 * vazada e ao aparelho furtado que continua com rede, que de outro modo renovaria o token para
 * sempre.
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
    private final PoliticaDeSenha politica;
    private final TermosDeUsoService termos;
    private final TransactionTemplate transacao;

    /**
     * Conferido no lugar da senha guardada quando o e-mail não tem credencial. Nasce do mesmo
     * codificador que grava as senhas, então custa o mesmo BCrypt que elas, e não é a senha de
     * ninguém: o texto codificado é aleatório e não fica guardado.
     */
    private final String hashSintetico;

    AutenticacaoService(CredencialRepository credenciais, UsuarioRepository usuarios,
            ContaRepository contas, PasswordEncoder encoder, EmissorDeToken emissor,
            ContaService contaService, ContencaoDeLogin contencao, PoliticaDeSenha politica,
            TermosDeUsoService termos, TransactionTemplate transacao) {
        this.credenciais = credenciais;
        this.usuarios = usuarios;
        this.contas = contas;
        this.encoder = encoder;
        this.emissor = emissor;
        this.contaService = contaService;
        this.contencao = contencao;
        this.politica = politica;
        this.termos = termos;
        this.transacao = transacao;
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
            token = autenticar(email, senha, origem);
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

    private String autenticar(String email, String senha, String origem) {
        Optional<Credencial> encontrada = credenciais.findByEmailIgnoreCase(email.trim());
        if (encontrada.isEmpty()) {
            // Confere a senha mesmo assim, contra o hash sintético: recusar sem o BCrypt responderia
            // mais rápido que a senha errada, e o tempo diria quais e-mails existem.
            encoder.matches(senha, hashSintetico);
            throw recusa("email_desconhecido", null, null, origem);
        }
        Credencial credencial = encontrada.get();
        ContaId conta = credencial.getContaId();

        if (!encoder.matches(senha, credencial.getSenhaHash())) {
            throw recusa("senha", conta, credencial.getUsuarioId(), origem);
        }

        // A partir daqui o tenant existe, e o usuário é lido sob o filtro automático como qualquer
        // outro dado de conta.
        Optional<Usuario> lido = TenantContext.executarComo(conta,
                () -> usuarios.findById(credencial.getUsuarioId()));
        if (lido.isEmpty() || !lido.get().isAtivo()) {
            throw recusa("usuario_inativo", conta, credencial.getUsuarioId(), origem);
        }
        Usuario usuario = lido.get();

        if (usuario.getPerfil() == Perfil.ADMIN) {
            TenantContext.executarComo(conta, () -> UsuarioContext.executarComo(
                    new UsuarioAutenticado(usuario.getId(), usuario.getPerfil()),
                    contaService::registrarPrimeiroAcessoSeNecessario));
        }

        String token = emissor.emitir(usuario.getId(), conta, usuario.getPerfil(),
                usuario.getVersaoDoAcesso());
        EventoDeSeguranca.de("login.certo")
                .com("conta", conta)
                .com("usuario", usuario.getId())
                .daOrigem(origem)
                .registrar();
        return token;
    }

    /** Registra a recusa e devolve a exceção, que é a mesma para todo motivo. */
    private static CredenciaisInvalidasException recusa(String motivo, ContaId conta,
            UUID usuario, String origem) {
        EventoDeSeguranca.de("login.recusado")
                .com("conta", conta)
                .com("usuario", usuario)
                .com("motivo", motivo)
                .daOrigem(origem)
                .registrarRecusa();
        return new CredenciaisInvalidasException();
    }

    /**
     * Troca a senha de quem está autenticado, que confirma a atual, e derruba todos os aparelhos
     * dele, menos este, que recebe o token novo.
     *
     * <p>A senha atual passa pela mesma contenção do login, pela origem e pelo e-mail vindo dela:
     * quem pegou um aparelho destravado não usa a troca para adivinhar a senha, e quem ataca não soma
     * tentativas alternando o login e a troca. A senha atual certa zera o par, como o login certo.
     *
     * <p>A senha nova é escolhida pela própria pessoa, então entra mesmo quando a lista de
     * vazamentos não responde. Igual à atual é recusada, para a troca pedida no primeiro acesso não
     * terminar com a senha que outra pessoa escolheu.
     *
     * <p><strong>Sem {@code @Transactional} no método.</strong> O BCrypt e a lista, que pode levar
     * segundos, rodam antes; só a escrita abre transação, com a linha do usuário travada até o commit,
     * para duas revogações simultâneas não gravarem a mesma versão. O tenant já está no contexto,
     * então cada leitura abre a própria sessão já na Conta certa.
     *
     * @param origem o IP de quem pede, já lido da cadeia de proxies confiáveis
     * @return o token novo deste aparelho, com a versão de acesso nova
     * @throws SenhaAtualIncorretaException se a senha atual não confere
     * @throws LoginContidoException se a origem, ou o e-mail vindo dela, errou demais na janela atual
     * @throws SenhaRecusadaException se a senha nova é igual à atual, curta, longa demais ou vazada
     */
    public String trocarSenha(String senhaAtual, String senhaNova, String origem) {
        UsuarioAutenticado atual = UsuarioContext.exigirAtual();
        ContaId contaId = TenantContext.exigirAtual();
        if (senhaAtual == null || senhaNova == null) {
            throw new IllegalArgumentException("informe a senha atual e a nova");
        }

        Credencial credencial = credencialDe(atual.usuarioId(), contaId);
        conferirSenhaAtual(credencial, senhaAtual, origem, SenhaAtualIncorretaException.Troca.SENHA);

        if (senhaNova.equals(senhaAtual)) {
            throw new SenhaRecusadaException("A senha nova precisa ser diferente da atual");
        }
        politica.exigirValidaEscolhidaPelaPropriaPessoa(senhaNova);
        String novoHash = encoder.encode(senhaNova);

        int versao = transacao.execute(status -> {
            Usuario usuario = usuarioTravado(atual.usuarioId());
            Credencial paraGravar = credencialDe(usuario.getId(), contaId);
            paraGravar.trocarSenha(novoHash);
            credenciais.save(paraGravar);
            usuario.revogarAcessos();
            usuarios.save(usuario);
            return usuario.getVersaoDoAcesso();
        });
        return emissor.emitir(atual.usuarioId(), contaId, atual.perfil(), versao);
    }

    /**
     * Troca o e-mail de entrada de quem está autenticado, que confirma a senha atual (LGPD, art. 18,
     * III).
     *
     * <p>O e-mail é credencial: a senha atual passa pela mesma contenção da troca de senha, pelo
     * e-mail atual, para quem pegou um aparelho destravado não trocá-lo e trancar o dono fora. Só
     * depois dela se pergunta se o e-mail novo já tem dono, então descobrir quais e-mails existem
     * pede a senha.
     *
     * <p>Os tokens continuam valendo, porque não levam o e-mail; o login seguinte é pelo novo. O novo
     * igual ao atual, sem diferença de maiúscula, termina sem efeito.
     *
     * <p><strong>Sem {@code @Transactional} no método</strong>, pelo mesmo motivo da troca de senha: o
     * BCrypt roda antes, e a escrita trava a linha do usuário, que a troca de senha e a redefinição
     * também travam antes de mexer na credencial.
     *
     * @param origem o IP de quem pede, já lido da cadeia de proxies confiáveis
     * @return se o e-mail mudou
     * @throws SenhaAtualIncorretaException se a senha atual não confere
     * @throws LoginContidoException se a origem, ou o e-mail vindo dela, errou demais na janela atual
     * @throws EmailJaCadastradoException se outra credencial, nesta ou em outra Conta, já usa o e-mail
     * @throws IllegalArgumentException se o e-mail novo está em branco ou passa do tamanho da coluna
     */
    public boolean trocarEmail(String senhaAtual, String emailNovo, String origem) {
        UsuarioAutenticado atual = UsuarioContext.exigirAtual();
        ContaId contaId = TenantContext.exigirAtual();
        if (senhaAtual == null || emailNovo == null) {
            throw new IllegalArgumentException("informe a senha atual e o e-mail novo");
        }

        Credencial credencial = credencialDe(atual.usuarioId(), contaId);
        conferirSenhaAtual(credencial, senhaAtual, origem, SenhaAtualIncorretaException.Troca.EMAIL);

        String novo = emailNovo.trim();
        if (novo.equalsIgnoreCase(credencial.getEmail())) {
            return false;
        }
        // Consultado antes de gravar, como na criação de usuário; o índice único continua como rede.
        if (credenciais.existsByEmailIgnoreCase(novo)) {
            throw new EmailJaCadastradoException(novo);
        }

        transacao.executeWithoutResult(status -> {
            Usuario usuario = usuarioTravado(atual.usuarioId());
            Credencial paraGravar = credencialDe(usuario.getId(), contaId);
            paraGravar.trocarEmail(novo);
            credenciais.save(paraGravar);
        });
        return true;
    }

    /**
     * Confere a senha atual de quem pede uma troca, contada na contenção do login pela origem e pelo
     * e-mail atual. Certa, zera o par, como o login certo; errada, a reserva fica, porque é a falha
     * que a contenção conta.
     */
    private void conferirSenhaAtual(Credencial credencial, String senhaAtual, String origem,
            SenhaAtualIncorretaException.Troca troca) {
        ContencaoDeLogin.Tentativa tentativa =
                contencao.reservar(origem, credencial.getEmail(), Instant.now());
        boolean confere;
        try {
            confere = encoder.matches(senhaAtual, credencial.getSenhaHash());
        } catch (RuntimeException erro) {
            // Um erro que não diz nada sobre a senha não conta como falha.
            contencao.desfazer(tentativa);
            throw erro;
        }
        if (!confere) {
            throw new SenhaAtualIncorretaException(troca);
        }
        contencao.registrarSucesso(tentativa);
    }

    /**
     * O nome, o e-mail de entrada e o perfil de quem está autenticado, para a própria pessoa ver o que
     * o Caixa Simples guarda do cadastro dela (LGPD, art. 18, I e II).
     *
     * <p>Fica fora da identidade de toda tela de propósito: o aplicativo guarda a identidade no
     * aparelho, e o e-mail só aparece quando a pessoa abre a tela dos próprios dados.
     */
    @Transactional(readOnly = true)
    public MeusDados meusDados() {
        UsuarioAutenticado atual = UsuarioContext.exigirAtual();
        ContaId contaId = TenantContext.exigirAtual();
        Usuario usuario = usuarios.findById(atual.usuarioId())
                .orElseThrow(() -> new IllegalStateException(
                        "usuario do contexto nao existe: " + atual.usuarioId()));
        Credencial credencial = credencialDe(usuario.getId(), contaId);
        return new MeusDados(usuario.getNome(), credencial.getEmail(), atual.perfil());
    }

    /** O cadastro de quem está autenticado, como a própria pessoa o vê. */
    public record MeusDados(String nome, String email, Perfil perfil) {
    }

    /**
     * Derruba todos os aparelhos de quem está autenticado, inclusive este, sem trocar a senha.
     *
     * <p>Serve ao aparelho furtado quando a senha não vazou. Não pede a senha atual, porque o pior que
     * alguém com o aparelho consegue com isto é sair também. Derrubar este junto é deliberado: se
     * ficasse, quem está com o aparelho furtado apertaria primeiro, expulsaria o dono e continuaria
     * dentro.
     */
    @Transactional
    public void sairDeTodosOsAparelhos() {
        UsuarioAutenticado atual = UsuarioContext.exigirAtual();
        Usuario usuario = usuarioTravado(atual.usuarioId());
        usuario.revogarAcessos();
        usuarios.save(usuario);
    }

    private Usuario usuarioTravado(UUID usuarioId) {
        return usuarios.buscarParaAtualizar(usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "usuario do contexto nao existe: " + usuarioId));
    }

    /**
     * A credencial do usuário do contexto. A tabela não tem filtro de tenant, porque o login a
     * consulta antes de existir um; a Conta é conferida aqui, como na inativação.
     */
    private Credencial credencialDe(UUID usuarioId, ContaId contaId) {
        Credencial credencial = credenciais.findByUsuarioId(usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "usuario do contexto sem credencial: " + usuarioId));
        if (!credencial.getContaId().equals(contaId)) {
            throw new IllegalStateException("credencial pertence a outra conta");
        }
        return credencial;
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
     * <p>A marca de senha provisória vem junto porque o aplicativo mostra só a tela de troca enquanto
     * ela valer, logo depois do login. A pendência dos termos vem pelo mesmo motivo: o administrador
     * só vê a tela dos termos até a Conta aceitar a versão vigente.
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

        boolean senhaProvisoria = credencialDe(usuario.getId(), contaId).isSenhaProvisoria();

        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);
        return new Identidade(usuario.getId(), usuario.getNome(), atual.perfil(),
                conta.getId(), conta.getNomeNegocio(), conta.getTipoNegocio(),
                conta.isEstoqueHabilitado(), conta.isNsuObrigatorio(), conta.getPlano(),
                conta.situacao(hoje),
                conta.getProximoVencimento(), conta.inicioDaSuspensao(), conta.recursos(hoje),
                senhaProvisoria, termos.pendentes());
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
     * @param senhaProvisoria   se a senha foi escolhida por outra pessoa e o aplicativo pede a troca
     * @param termosPendentes   se a Conta ainda não aceitou a versão vigente dos termos de uso; o
     *                          aplicativo pede o aceite só ao administrador
     */
    public record Identidade(UUID usuarioId, String nome, Perfil perfil, UUID contaId,
            String nomeNegocio, String tipoNegocio, boolean estoqueHabilitado,
            boolean nsuObrigatorio, Plano plano,
            SituacaoDoPlano situacaoDoPlano, LocalDate vencimentoDoPlano,
            LocalDate inicioDaSuspensao, Set<RecursoDoPlano> recursos, boolean senhaProvisoria,
            boolean termosPendentes) {
    }
}
