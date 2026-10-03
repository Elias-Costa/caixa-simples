package br.com.caixasimples.contas.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.ContencaoDeLogin;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.EmissorDeToken;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * O login com o banco fora do ar, sem Spring: o repositório simulado falha em toda consulta.
 *
 * <p>A recusa de credencial conta na contenção, e isso a integração pela API já prova. Aqui fica o
 * outro lado: o erro que não diz nada sobre a senha devolve a reserva, e por isso uma queda do banco
 * não deixa bloqueado, quando ele volta, quem tentou entrar durante ela.
 */
class AutenticacaoServiceTest {

    private static final String ORIGEM = "203.0.113.60";
    private static final String EMAIL = "ana@exemplo.test";

    @Test
    @DisplayName("o erro do banco no login não conta como falha, nem do par nem da origem")
    void erroDoBancoNaoConta() {
        CredencialRepository credenciais = mock(CredencialRepository.class);
        when(credenciais.findByEmailIgnoreCase(anyString()))
                .thenThrow(new DataAccessResourceFailureException("banco fora do ar"));
        ContencaoDeLogin contencao = new ContencaoDeLogin();
        // Custo 4, o mínimo do BCrypt: aqui o custo da conferência não é o assunto.
        AutenticacaoService autenticacao = new AutenticacaoService(credenciais,
                mock(UsuarioRepository.class), mock(ContaRepository.class), new BCryptPasswordEncoder(4),
                mock(EmissorDeToken.class), mock(ContaService.class), contencao);

        // Mais que os dez do par e os trinta da origem.
        for (int i = 0; i < 31; i++) {
            assertThatThrownBy(() -> autenticacao.entrar(EMAIL, "uma senha longa de teste", ORIGEM))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        assertThatCode(() -> contencao.reservar(ORIGEM, EMAIL, Instant.now())).doesNotThrowAnyException();
    }
}
