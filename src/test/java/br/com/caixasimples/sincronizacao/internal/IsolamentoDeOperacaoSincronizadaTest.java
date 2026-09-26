package br.com.caixasimples.sincronizacao.internal;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.RevisaoNaoEncontradaException;
import br.com.caixasimples.sincronizacao.application.RevisaoService;
import br.com.caixasimples.sincronizacao.application.RevisaoService.RevisaoDeOperacao;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * O registro de operações sincronizadas é por conta (RNF05): gravado na conta A, não aparece para
 * a conta B, nem pelo id da operação nem numa listagem, e o mesmo id pode ser gravado na conta B
 * sem tocar o da A. A lista de revisões e a conferência seguem o mesmo filtro.
 */
class IsolamentoDeOperacaoSincronizadaTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired OperacaoSincronizadaRepository registros;
    @Autowired SincronizacaoService sincronizacao;
    @Autowired RevisaoService revisoes;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired ObjectMapper json;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("a operação gravada na conta A não existe para a conta B, e o mesmo id convive nas duas")
    void registroNaoAtravessaConta() {
        ContaCriada contaA = criador.criar("Loja A", SENHA);
        ContaCriada contaB = criador.criar("Loja B", SENHA);
        GestoDeTeste daContaA = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Ana", "contato", "71 98888-0000"));

        contaA.comoUsuario(() -> sincronizacao.sincronizar(List.of(daContaA.recebida(json))));

        contaB.comoUsuario(() -> {
            assertThat(registros.findByOperacaoId(daContaA.operacaoId())).isEmpty();
            assertThat(registros.findByOperacaoIdIn(List.of(daContaA.operacaoId()))).isEmpty();
            assertThat(registros.findAll()).isEmpty();
        });

        GestoDeTeste daContaB = new GestoDeTeste(daContaA.operacaoId(), UUID.randomUUID(),
                "cliente.criar", conteudo("nome", "Bruno", "contato", null), null, List.of(),
                daContaA.criadoEm());
        contaB.comoUsuario(() -> sincronizacao.sincronizar(List.of(daContaB.recebida(json))));

        contaA.comoUsuario(() -> {
            OperacaoSincronizada gravada = registros.findByOperacaoId(daContaA.operacaoId())
                    .orElseThrow();
            assertThat(gravada.getContaId()).isEqualTo(contaA.contaId());
            assertThat(gravada.getRegistroId()).isEqualTo(daContaA.registroId());
            assertThat(gravada.getResultado()).isEqualTo(Resultado.APLICADA);
            // O conteúdo vai e volta do jsonb sem perder campo nem valor.
            assertThat(json.readTree(gravada.getPayload()))
                    .isEqualTo(json.valueToTree(daContaA.payload()));
            assertThat(registros.findAll()).hasSize(1);
        });
        contaB.comoUsuario(() -> assertThat(registros.findByOperacaoId(daContaA.operacaoId())
                .orElseThrow().getRegistroId()).isEqualTo(daContaB.registroId()));
    }

    @Test
    @DisplayName("a revisão da conta A não aparece nas listas da conta B, e a B não a confere")
    void revisaoNaoAtravessaConta() {
        ContaCriada contaA = criador.criar("Loja A", SENHA);
        ContaCriada contaB = criador.criar("Loja B", SENHA);
        GestoDeTeste adiantado = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Ana", "contato", null))
                .registradoEm(Instant.now().plus(Duration.ofHours(1))
                        .truncatedTo(ChronoUnit.MILLIS));
        GestoDeTeste conferido = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Bia", "contato", null))
                .registradoEm(Instant.now().plus(Duration.ofHours(1))
                        .truncatedTo(ChronoUnit.MILLIS));
        contaA.comoUsuario(() -> {
            sincronizacao.sincronizar(List.of(adiantado.recebida(json),
                    conferido.recebida(json)));
            revisoes.conferir(conferido.operacaoId());
        });
        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);

        contaB.comoUsuario(() -> {
            assertThat(revisoes.pendentes()).isEmpty();
            assertThat(revisoes.conferidasNoDia(hoje)).isEmpty();
            assertThatThrownBy(() -> revisoes.conferir(adiantado.operacaoId()))
                    .isInstanceOf(RevisaoNaoEncontradaException.class);
        });

        contaA.comoUsuario(() -> {
            assertThat(revisoes.pendentes()).extracting(RevisaoDeOperacao::operacaoId)
                    .containsExactly(adiantado.operacaoId());
            assertThat(revisoes.conferidasNoDia(hoje)).extracting(RevisaoDeOperacao::conferidaPor)
                    .containsExactly(contaA.usuarioId());
        });
    }
}
