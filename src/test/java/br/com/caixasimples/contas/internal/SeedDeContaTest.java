package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.shared.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * O provisionamento de conta por seed, executado como a aplicação o executa ao subir.
 *
 * <p>Sobe um contexto próprio, com o perfil {@code seed} ativo e os dados da conta nas
 * propriedades, para o seed rodar como bean de verdade, na subida, e não construído à mão: uma
 * execução real revelou que o usuário administrador nascia no tenant sentinela, e não na conta
 * criada, e o defeito só aparece com o bean que o Spring monta, porque é a ordem entre a
 * transação e a conta no contexto que o causa.
 */
@ActiveProfiles("seed")
@TestPropertySource(properties = {
        "caixa-simples.seed.nome-negocio=Cafeteria Aurora",
        "caixa-simples.seed.tipo-negocio=cafeteria",
        "caixa-simples.seed.nome-usuario=Ana",
        "caixa-simples.seed.email=seed.aurora@exemplo.test",
        "caixa-simples.seed.senha=uma senha longa de seed"
})
class SeedDeContaTest extends TesteDeIntegracao {

    private static final String EMAIL = "seed.aurora@exemplo.test";
    private static final String SENHA = "uma senha longa de seed";

    @Autowired
    private SeedDeConta seed;

    @Autowired
    private ContaRepository contas;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private CredencialRepository credenciais;

    @Autowired
    private AutenticacaoService autenticacao;

    @Test
    @DisplayName("na subida, cria a conta com o administrador dentro dela, e o login funciona")
    void criaContaComAdministradorDentroDela() {
        Credencial credencial = credenciais.findByEmailIgnoreCase(EMAIL).orElseThrow();

        Optional<Usuario> usuario = TenantContext.executarComo(credencial.getContaId(),
                () -> usuarios.findById(credencial.getUsuarioId()));

        assertThat(usuario).isPresent();
        assertThat(usuario.get().getNome()).isEqualTo("Ana");
        assertThat(contas.findById(credencial.getContaId().valor()))
                .isPresent()
                .get()
                .extracting(Conta::getNomeNegocio, Conta::getTipoNegocio)
                .containsExactly("Cafeteria Aurora", "cafeteria");
        assertThat(autenticacao.entrar(EMAIL, SENHA)).isNotBlank();
    }

    @Test
    @DisplayName("executar de novo com o mesmo e-mail não cria segunda conta")
    void segundaExecucaoNaoDuplica() {
        long contasAntes = contas.count();

        seed.run(new DefaultApplicationArguments());

        assertThat(contas.count()).isEqualTo(contasAntes);
    }
}
