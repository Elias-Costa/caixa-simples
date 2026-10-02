package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.RegistroDeRemocoesDeTeste;
import br.com.caixasimples.RegistroDeRemocoesDeTeste.Remocao;
import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.application.UltimoAdministradorException;
import br.com.caixasimples.contas.application.UsuarioNaoEncontradoException;
import br.com.caixasimples.contas.application.UsuarioService;
import br.com.caixasimples.contas.application.UsuarioService.UsuarioDaConta;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.RegistroDeRemocoes.Tipo;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inativações de administrador que chegam ao mesmo tempo na mesma Conta.
 *
 * <p>A guarda que impede inativar o último administrador ativo conta quantos restam. Duas
 * inativações de administradores diferentes que contassem antes de qualquer uma confirmar veriam
 * os mesmos dois, passariam as duas, gravariam cada uma o registro externo da remoção e deixariam a
 * Conta sem ninguém que a administre. A inativação trava a linha da Conta antes de ler o usuário e
 * contar, então a segunda espera a primeira confirmar e decide sobre o que ela deixou.
 *
 * <h2>Como a disputa fica determinística</h2>
 *
 * <p>Uma transação prende, sem alterá-la, a linha do usuário que a primeira inativação grava, e as
 * inativações começam em threads próprias, uma depois da outra: cada uma só começa quando as
 * anteriores estão paradas numa trava ou já terminaram, e a transação confirma quando todas estão
 * assim. A primeira para ao gravar o usuário, depois de decidir e de gravar o registro externo,
 * ainda sem confirmar. Sem a trava da Conta, a segunda decide nessa janela, sobre a contagem antiga;
 * com ela, para na trava da Conta, atrás da primeira e não da transação que prende o usuário, e só
 * decide depois que a primeira confirma. Nada depende de pausa nem de sorte com o escalonador.
 */
class InativacoesSimultaneasTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    /** A trava que a gravação do usuário disputa. */
    private static final String PRENDE_O_USUARIO =
            "select id from usuario where id = :id and conta_id = :conta for no key update";

    /** Mais forte que a trava do serviço: barra também quem só conferisse a chave da Conta. */
    private static final String PRENDE_A_CONTA = "select id from conta where id = :conta for update";

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private UsuarioService usuarios;

    @Autowired
    private RegistroDeRemocoesDeTeste registroDeRemocoes;

    @Autowired
    private AutenticadorDeTeste autenticador;

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
    @DisplayName("um administrador inativa o outro ao mesmo tempo: uma inativação confirma, e a outra é recusada sem gravar registro externo")
    void umAdministradorInativaOOutroAoMesmoTempo() {
        ContaCriada dono = criador.criar("Salao Aurora", SENHA_DE_TESTE);
        UsuarioCriado socia = criador.criarAdminEm(dono.contaId(), "Socia");

        List<CompletableFuture<Object>> disputa = comOUsuarioPreso(dono, socia.usuarioId(),
                inativacaoPor(dono, socia.usuarioId()),
                inativacaoPor(socia, dono.usuarioId()));

        aguardar(disputa);
        dono.comoUsuario(() -> assertThat(usuarios.listar())
                .as("resta um administrador ativo")
                .extracting(UsuarioDaConta::id, UsuarioDaConta::ativo)
                .containsExactlyInAnyOrder(
                        tuple(dono.usuarioId(), true),
                        tuple(socia.usuarioId(), false)));
        assertThat(remocoesDa(dono.contaId()))
                .as("só a inativação que confirmou gravou o registro externo")
                .extracting(Remocao::tipo, Remocao::registroId, Remocao::solicitadoPor)
                .containsExactly(tuple(Tipo.USUARIO, socia.usuarioId(), dono.usuarioId()));
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        assertThat(disputa.get(1)).failsWithin(ESPERA)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(UltimoAdministradorException.class);
        assertThat(autenticador.tokenDe(dono))
                .as("a credencial de quem ficou não foi apagada")
                .isNotBlank();
    }

    @Test
    @DisplayName("a mesma inativação pedida duas vezes ao mesmo tempo: a segunda espera, encontra o usuário já inativo e não grava outro registro externo")
    void mesmaInativacaoDuasVezesAoMesmoTempo() {
        ContaCriada dono = criador.criar("Barbearia do Largo", SENHA_DE_TESTE);
        UsuarioCriado socio = criador.criarAdminEm(dono.contaId(), "Socio");

        List<CompletableFuture<Object>> disputa = comOUsuarioPreso(dono, socio.usuarioId(),
                inativacaoPor(dono, socio.usuarioId()),
                inativacaoPor(dono, socio.usuarioId()));

        aguardar(disputa);
        dono.comoUsuario(() -> assertThat(usuarios.listar())
                .extracting(UsuarioDaConta::id, UsuarioDaConta::ativo)
                .containsExactlyInAnyOrder(
                        tuple(dono.usuarioId(), true),
                        tuple(socio.usuarioId(), false)));
        assertThat(remocoesDa(dono.contaId()))
                .as("uma remoção, um registro externo")
                .extracting(Remocao::tipo, Remocao::registroId, Remocao::solicitadoPor)
                .containsExactly(tuple(Tipo.USUARIO, socio.usuarioId(), dono.usuarioId()));
        assertThat(disputa).allSatisfy(inativacao -> assertThat(inativacao).succeedsWithin(ESPERA));
    }

    @Test
    @DisplayName("com a Conta A presa, a inativação na Conta B termina sem esperar, e o administrador de A continua fora do alcance de B (RNF05)")
    void travaDeUmaContaNaoAlcancaOutra() {
        ContaCriada donoA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UsuarioCriado sociaA = criador.criarAdminEm(donoA.contaId(), "Socia de A");
        ContaCriada donoB = criador.criar("Loja da Esquina", SENHA_DE_TESTE);
        UsuarioCriado socioB = criador.criarAdminEm(donoB.contaId(), "Socio de B");

        ExecutorService threadDaOutraConta = Executors.newSingleThreadExecutor();
        try {
            donoA.comoUsuario(() -> transacao.executeWithoutResult(status -> {
                prenderAConta(donoA.contaId());

                CompletableFuture<Object> naPropriaConta = CompletableFuture.supplyAsync(
                        inativacaoPor(donoB, socioB.usuarioId()), threadDaOutraConta);
                CompletableFuture<Object> comIdDeA = CompletableFuture.supplyAsync(
                        inativacaoPor(donoB, sociaA.usuarioId()), threadDaOutraConta);

                // As duas terminam com a linha de A ainda presa: nenhuma esperou por ela.
                assertThat(naPropriaConta).succeedsWithin(ESPERA);
                assertThat(comIdDeA).failsWithin(ESPERA)
                        .withThrowableOfType(ExecutionException.class)
                        .withCauseInstanceOf(UsuarioNaoEncontradoException.class);
                assertThat(quantasEsperam()).isZero();
            }));
        } finally {
            threadDaOutraConta.shutdown();
        }

        donoA.comoUsuario(() -> assertThat(usuarios.listar())
                .extracting(UsuarioDaConta::id, UsuarioDaConta::ativo)
                .containsExactlyInAnyOrder(
                        tuple(donoA.usuarioId(), true),
                        tuple(sociaA.usuarioId(), true)));
        assertThat(remocoesDa(donoA.contaId())).isEmpty();
        donoB.comoUsuario(() -> assertThat(usuarios.listar())
                .extracting(UsuarioDaConta::id, UsuarioDaConta::ativo)
                .containsExactlyInAnyOrder(
                        tuple(donoB.usuarioId(), true),
                        tuple(socioB.usuarioId(), false)));
        assertThat(remocoesDa(donoB.contaId()))
                .extracting(Remocao::tipo, Remocao::registroId, Remocao::solicitadoPor)
                .containsExactly(tuple(Tipo.USUARIO, socioB.usuarioId(), donoB.usuarioId()));
    }

    /**
     * Prende a linha do usuário numa transação que não a altera e começa cada inativação numa
     * thread própria. A seguinte só começa quando as anteriores estão paradas numa trava ou
     * terminaram, e a transação só confirma quando todas estão assim.
     *
     * @return as inativações, na ordem dada; cada uma termina depois que a linha é solta
     */
    @SafeVarargs
    private <T> List<CompletableFuture<T>> comOUsuarioPreso(ContaCriada dono, UUID usuarioId,
            Supplier<T>... inativacoes) {
        ExecutorService threads = Executors.newFixedThreadPool(inativacoes.length);
        try {
            return dono.comoUsuario(() -> transacao.execute(status -> {
                prenderOUsuario(usuarioId, dono.contaId());
                List<CompletableFuture<T>> iniciadas = new ArrayList<>();
                for (Supplier<T> inativacao : inativacoes) {
                    iniciadas.add(CompletableFuture.supplyAsync(inativacao, threads));
                    await().atMost(ESPERA).until(() ->
                            quantasEsperam() + terminadas(iniciadas) == iniciadas.size());
                }
                return iniciadas;
            }));
        } finally {
            // As threads terminam o que começaram e só então se encerram.
            threads.shutdown();
        }
    }

    private void prenderOUsuario(UUID usuarioId, ContaId conta) {
        jdbc.sql(PRENDE_O_USUARIO)
                .param("id", usuarioId)
                .param("conta", conta.valor())
                .query(UUID.class)
                .single();
    }

    private void prenderAConta(ContaId conta) {
        jdbc.sql(PRENDE_A_CONTA)
                .param("conta", conta.valor())
                .query(UUID.class)
                .single();
    }

    /**
     * Quantas conexões estão paradas esperando uma trava, de quem for. A segunda inativação espera
     * a primeira, e não a transação que prendeu a linha, então contar só quem espera por esta
     * deixaria a segunda de fora. Lê {@code pg_locks}, que o PostgreSQL monta na hora da consulta.
     */
    private long quantasEsperam() {
        return jdbc.sql("select count(*) from pg_locks where not granted")
                .query(Long.class)
                .single();
    }

    private static long terminadas(List<? extends CompletableFuture<?>> operacoes) {
        return operacoes.stream().filter(CompletableFuture::isDone).count();
    }

    private static void aguardar(List<? extends CompletableFuture<?>> operacoes) {
        await().atMost(ESPERA).until(() -> terminadas(operacoes) == operacoes.size());
    }

    /** A inativação pedida pela pessoa que criou a Conta, no formato que a disputa recebe. */
    private Supplier<Object> inativacaoPor(ContaCriada quem, UUID alvo) {
        return () -> quem.comoUsuario(() -> {
            usuarios.inativar(alvo);
            return null;
        });
    }

    /** O mesmo, pedido por outro administrador da Conta. */
    private Supplier<Object> inativacaoPor(UsuarioCriado quem, UUID alvo) {
        return () -> quem.comoUsuario(() -> {
            usuarios.inativar(alvo);
            return null;
        });
    }

    /** O registro externo é compartilhado pela suíte; cada teste lê só o da própria Conta. */
    private List<Remocao> remocoesDa(ContaId conta) {
        return registroDeRemocoes.registros().stream()
                .filter(remocao -> remocao.conta().equals(conta))
                .toList();
    }
}
