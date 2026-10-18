package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.contas.application.AutenticacaoService.Identidade;
import br.com.caixasimples.contas.application.AutenticacaoService.MeusDados;
import br.com.caixasimples.contas.application.EmailJaCadastradoException;
import br.com.caixasimples.contas.application.SenhaAtualIncorretaException;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import br.com.caixasimples.contas.internal.LoginContidoException;
import br.com.caixasimples.contas.internal.SenhaRecusadaException;
import br.com.caixasimples.shared.EventoDeSeguranca;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP da autenticação. Rotas sob {@code /api}, sem versão no caminho.
 *
 * <p>É o molde dos controllers do projeto: pedido e resposta são records aninhados, o corpo do
 * pedido é validado com {@code @Valid} (campo em branco vira 400 antes de chegar ao caso de uso),
 * o controller só traduz e delega, e a exceção que só este módulo lança é traduzida aqui mesmo.
 * O que é transversal, como a recusa por perfil virar 403, fica no tratador de {@code shared}.
 *
 * <p>A troca de senha e a de e-mail, a recusa delas e a saída de todos os aparelhos vão ao log como
 * evento de segurança, com a origem, que só a borda HTTP conhece. O login é registrado no caso de
 * uso, o único que sabe o motivo da recusa.
 */
@RestController
@RequestMapping("/api/auth")
class AutenticacaoController {

    private final AutenticacaoService autenticacao;

    AutenticacaoController(AutenticacaoService autenticacao) {
        this.autenticacao = autenticacao;
    }

    /**
     * A origem é o endereço que o servidor já leu da direita da cadeia de proxies confiáveis, antes
     * do Spring: o que o cliente escreve à esquerda do cabeçalho nunca chega aqui.
     */
    @PostMapping("/login")
    RespostaDeLogin login(@Valid @RequestBody PedidoDeLogin pedido, HttpServletRequest requisicao) {
        return new RespostaDeLogin(
                autenticacao.entrar(pedido.email(), pedido.senha(), requisicao.getRemoteAddr()));
    }

    /**
     * Troca a senha de quem está autenticado e derruba os outros aparelhos dele.
     *
     * <p>O token novo deste aparelho vem no corpo, como no login. O que o filtro de renovação pôs no
     * cabeçalho foi emitido antes da troca, com a versão de acesso anterior, e já não vale.
     */
    @PostMapping("/senha")
    RespostaDeTrocaDeSenha trocarSenha(@Valid @RequestBody PedidoDeTrocaDeSenha pedido,
            HttpServletRequest requisicao) {
        String token = autenticacao.trocarSenha(pedido.senhaAtual(), pedido.senhaNova(),
                requisicao.getRemoteAddr());
        EventoDeSeguranca.de("senha.trocada")
                .doContexto()
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
        return new RespostaDeTrocaDeSenha(token);
    }

    /**
     * Troca o e-mail de entrada de quem está autenticado. Os tokens seguem, porque não levam o
     * e-mail; o evento vai ao log só quando o e-mail mudou, e nunca com ele.
     */
    @PostMapping("/email")
    ResponseEntity<Void> trocarEmail(@Valid @RequestBody PedidoDeTrocaDeEmail pedido,
            HttpServletRequest requisicao) {
        boolean trocou = autenticacao.trocarEmail(pedido.senhaAtual(), pedido.emailNovo(),
                requisicao.getRemoteAddr());
        if (trocou) {
            EventoDeSeguranca.de("email.trocado")
                    .doContexto()
                    .daOrigem(requisicao.getRemoteAddr())
                    .registrar();
        }
        return ResponseEntity.noContent().build();
    }

    /** O cadastro de quem está autenticado: nome, e-mail de entrada e perfil. */
    @GetMapping("/meus-dados")
    RespostaDeMeusDados meusDados() {
        MeusDados dados = autenticacao.meusDados();
        return new RespostaDeMeusDados(dados.nome(), dados.email(), dados.perfil().name());
    }

    /** Derruba todos os aparelhos de quem está autenticado, inclusive este. */
    @PostMapping("/sair-de-todos")
    ResponseEntity<Void> sairDeTodos(HttpServletRequest requisicao) {
        autenticacao.sairDeTodosOsAparelhos();
        EventoDeSeguranca.de("acessos.revogados")
                .doContexto()
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
        return ResponseEntity.noContent().build();
    }

    /**
     * Quem está autenticado agora, e em que negócio.
     *
     * <p>É o que o aplicativo mostra no cabeçalho de toda tela, e é também o endpoint protegido
     * pelo qual se verifica, por HTTP, que requisição sem token é recusada, que o tenant do
     * contexto vem do claim, não do pedido, e que o perfil é o do banco, não o do claim, de modo
     * que um usuário inativado deixa de entrar na requisição seguinte.
     */
    @GetMapping("/eu")
    RespostaDeIdentidade eu() {
        Identidade identidade = autenticacao.identidade();
        return new RespostaDeIdentidade(
                identidade.usuarioId(),
                identidade.nome(),
                identidade.perfil().name(),
                identidade.contaId(),
                identidade.nomeNegocio(),
                identidade.tipoNegocio(),
                identidade.estoqueHabilitado(),
                identidade.nsuObrigatorio(),
                identidade.plano().name(),
                identidade.situacaoDoPlano().name(),
                identidade.vencimentoDoPlano(),
                identidade.inicioDaSuspensao(),
                identidade.recursos().stream().map(RecursoDoPlano::name).toList(),
                identidade.senhaProvisoria(),
                identidade.termosPendentes());
    }

    /**
     * Login recusado vira 401 sem corpo detalhado. A mensagem é sempre a mesma, e o motivo real não
     * viaja para o cliente.
     */
    @ExceptionHandler(CredenciaisInvalidasException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void credenciaisInvalidas() {
        // sem corpo de propósito
    }

    /**
     * Tentativas demais da mesma origem, ou do mesmo e-mail vindo dela, viram 429 com a espera até a
     * janela acabar: em segundos no Retry-After e em minutos na mensagem, que a tela de login mostra.
     * Os dois arredondam para cima, para quem esperar o que foi dito não ser recusado de novo.
     */
    @ExceptionHandler(LoginContidoException.class)
    ResponseEntity<ProblemDetail> loginContido(LoginContidoException contido) {
        long segundos = (contido.espera().toMillis() + 999) / 1000;
        long minutos = (segundos + 59) / 60;
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Muitas tentativas de entrar. Tente de novo em " + minutos
                        + (minutos == 1 ? " minuto." : " minutos."));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(segundos))
                .body(problema);
    }

    /**
     * A senha atual errada responde 400, e não 401: quem pede a troca está autenticado, e o
     * aplicativo trata o 401 como sessão encerrada.
     *
     * <p>A recusa vai ao log com o nome da troca que tentou, a da senha ou a do e-mail, e conta no
     * alerta de rajada como um login recusado, porque passa pela mesma contenção dele.
     */
    @ExceptionHandler(SenhaAtualIncorretaException.class)
    ProblemDetail senhaAtualIncorreta(SenhaAtualIncorretaException excecao,
            HttpServletRequest requisicao) {
        String evento = excecao.troca() == SenhaAtualIncorretaException.Troca.EMAIL
                ? "email.troca_recusada"
                : "senha.troca_recusada";
        EventoDeSeguranca.de(evento)
                .doContexto()
                .com("motivo", "senha_atual")
                .daOrigem(requisicao.getRemoteAddr())
                .registrarRecusa();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, excecao.getMessage());
    }

    @ExceptionHandler(SenhaRecusadaException.class)
    ProblemDetail senhaRecusada(SenhaRecusadaException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, excecao.getMessage());
    }

    /** O e-mail novo já entra em outra credencial, desta ou de outra Conta, como na criação. */
    @ExceptionHandler(EmailJaCadastradoException.class)
    ProblemDetail emailJaCadastrado(EmailJaCadastradoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, excecao.getMessage());
    }

    record PedidoDeLogin(@NotBlank String email, @NotBlank String senha) {
    }

    record RespostaDeLogin(String token) {
    }

    record PedidoDeTrocaDeSenha(@NotBlank String senhaAtual, @NotBlank String senhaNova) {
    }

    record RespostaDeTrocaDeSenha(String token) {
    }

    record PedidoDeTrocaDeEmail(@NotBlank String senhaAtual, @NotBlank @Email String emailNovo) {
    }

    record RespostaDeMeusDados(String nome, String email, String perfil) {
    }

    /**
     * @param tipoNegocio       omitido no JSON quando a conta não tem tipo
     * @param vencimentoDoPlano omitido no plano gratuito
     * @param inicioDaSuspensao omitido no plano gratuito
     * @param senhaProvisoria   verdadeiro enquanto a senha for a que outra pessoa escolheu
     * @param termosPendentes   verdadeiro enquanto a Conta não aceitou a versão vigente dos termos
     */
    record RespostaDeIdentidade(UUID usuarioId, String nome, String perfil, UUID contaId,
            String nomeNegocio, String tipoNegocio, boolean estoqueHabilitado,
            boolean nsuObrigatorio, String plano, String situacaoDoPlano,
            LocalDate vencimentoDoPlano, LocalDate inicioDaSuspensao, List<String> recursos,
            boolean senhaProvisoria, boolean termosPendentes) {
    }
}
