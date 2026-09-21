package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.TenantNaoResolvidoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A pergunta que os outros módulos fazem à conta em operação.
 *
 * <p>O que importa provar é de onde vem a conta: do contexto, e só dele. Não existe assinatura
 * por onde passar outra conta, então o teste negativo é a ausência de tenant, e não a conta B
 * lendo a conta A.
 */
class ContaServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ContaService contaService;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta nova nasce com o controle de estoque desligado, e ligar é por conta (RF17)")
    void estoqueNasceDesligadoELigaPorConta() {
        ContaCriada cafeteria = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada salao = criador.criar("Salao Vizinho", SENHA_DE_TESTE);

        assertThat(cafeteria.comoUsuario(contaService::estoqueHabilitado))
                .isFalse();

        criador.habilitarEstoque(cafeteria.contaId());

        assertThat(cafeteria.comoUsuario(contaService::estoqueHabilitado))
                .isTrue();
        assertThat(salao.comoUsuario(contaService::estoqueHabilitado))
                .as("ligar numa conta não liga na outra")
                .isFalse();
    }

    @Test
    @DisplayName("sem conta no contexto a pergunta falha, em vez de responder por conta nenhuma")
    void semTenantFalha() {
        assertThatExceptionOfType(TenantNaoResolvidoException.class)
                .isThrownBy(contaService::estoqueHabilitado);
    }
}
