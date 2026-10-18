package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.application.UsuarioService;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A correção do nome que chega ao mesmo tempo que uma revogação dos acessos do mesmo Usuário.
 *
 * <p>A gravação do Usuário escreve a linha inteira, inclusive a versão de acesso que vai em todo
 * token. Uma correção que lesse a linha sem trava, antes de uma troca de senha confirmar, gravaria de
 * volta a versão antiga, e os aparelhos que a troca derrubou voltariam a entrar. A correção lê com a
 * trava da linha, então espera a revogação confirmar e grava a versão nova.
 *
 * <h2>Como a disputa fica determinística</h2>
 *
 * <p>Uma transação sobe a versão de acesso, como a troca de senha, e fica sem confirmar. A correção
 * começa numa thread própria, e a transação só confirma quando ela está parada numa trava. Com a
 * trava na leitura, ela para antes de ler e lê a versão nova; sem ela, leria a versão antiga na hora
 * e pararia só ao gravar, e gravaria a antiga por cima. Nada depende de pausa nem de sorte com o
 * escalonador.
 */
class CorrecaoDoNomeSimultaneaTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    /** O que a troca de senha faz com a linha, sem confirmar. */
    private static final String REVOGA_OS_ACESSOS = "update usuario"
            + " set versao_do_acesso = versao_do_acesso + 1 where id = :id and conta_id = :conta";

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("a correção do nome feita durante uma revogação espera por ela e não desfaz a versão de acesso")
    void correcaoNaoDesfazARevogacao() {
        ContaCriada dono = criador.criar("Papelaria do Centro", SENHA_DE_TESTE);
        UsuarioCriado operador = criador.criarOperadorEm(dono.contaId(), "Atendente");

        ExecutorService thread = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> correcao;
        try {
            correcao = dono.comoUsuario(() -> transacao.execute(status -> {
                jdbc.sql(REVOGA_OS_ACESSOS)
                        .param("id", operador.usuarioId())
                        .param("conta", dono.contaId().valor())
                        .update();
                CompletableFuture<Void> iniciada = CompletableFuture.runAsync(() ->
                        dono.comoUsuario(() -> usuarioService.corrigirNome(operador.usuarioId(),
                                "Atendente Joana")), thread);
                await().atMost(ESPERA).until(() -> quantasEsperam() == 1 || iniciada.isDone());
                return iniciada;
            }));
        } finally {
            thread.shutdown();
        }

        assertThat(correcao).succeedsWithin(ESPERA);
        Usuario gravado = TenantContext.executarComo(dono.contaId(),
                () -> usuarios.findById(operador.usuarioId()).orElseThrow());
        assertThat(gravado.getNome()).isEqualTo("Atendente Joana");
        assertThat(gravado.getVersaoDoAcesso())
                .as("a revogação confirmada antes da correção continua valendo")
                .isEqualTo(1);
    }

    /** Quantas conexões estão paradas esperando uma trava, lido de {@code pg_locks}. */
    private long quantasEsperam() {
        return jdbc.sql("select count(*) from pg_locks where not granted")
                .query(Long.class)
                .single();
    }
}
