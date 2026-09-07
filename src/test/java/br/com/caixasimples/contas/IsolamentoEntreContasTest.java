package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Etapa 0.9 do plano — RNF05, o requisito que nao admite grau.
 *
 * <p>Este teste e o molde para todo dado novo do sistema: grava na conta A, consulta como conta B,
 * espera vazio. E o passo 6 da skill {@code nova-entidade-multitenant}, e nenhuma entidade nova
 * fecha sem o equivalente dele.
 *
 * <p>Prova por construcao que o filtro e do Hibernate, nao do codigo: em nenhum ponto abaixo
 * existe {@code WHERE conta_id = ?}, e ainda assim a conta B nao ve nada da conta A.
 */
class IsolamentoEntreContasTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private CredencialRepository credenciais;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta B nao enxerga usuario da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaUsuarioDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Piloto", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(usuarios.findById(contaA.usuarioId()))
                    .as("findById atravessando tenant")
                    .isEmpty();
            assertThat(usuarios.findAll())
                    .as("listagem da conta B")
                    .extracting(Usuario::getId)
                    .doesNotContain(contaA.usuarioId());
            assertThat(usuarios.findByAtivoTrue())
                    .as("derived query da conta B")
                    .extracting(Usuario::getId)
                    .doesNotContain(contaA.usuarioId());
        });

        // E a conta A continua vendo o proprio dado — filtro nao pode ser "esconde de todos".
        TenantContext.executarComo(contaA.contaId(), () -> {
            assertThat(usuarios.findById(contaA.usuarioId())).isPresent();
            assertThat(usuarios.findAll())
                    .extracting(Usuario::getId)
                    .containsExactly(contaA.usuarioId());
        });
    }

    @Test
    @DisplayName("contaId de um usuario vem do contexto, nunca de parametro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Loja Teste", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(usuarios.findById(conta.usuarioId()))
                        .get()
                        .extracting(Usuario::getContaId)
                        .isEqualTo(conta.contaId()));
    }

    @Test
    @DisplayName("credencial e consultavel sem tenant e resolve para a conta certa")
    void credencialResolveAContaSemTenantNoContexto() {
        ContaCriada contaA = criador.criar("Negocio A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Negocio B", SENHA_DE_TESTE);

        // Sem nenhum tenant no contexto — e exatamente a situacao do login, e o motivo de a
        // credencial nao ter @TenantId (D9).
        assertThat(TenantContext.atual()).isEmpty();

        assertThat(credenciais.findByEmailIgnoreCase(contaA.email()))
                .get()
                .extracting(Credencial::getContaId)
                .isEqualTo(contaA.contaId());

        assertThat(credenciais.findByEmailIgnoreCase(contaB.email()))
                .get()
                .extracting(Credencial::getContaId)
                .isEqualTo(contaB.contaId());
    }
}
