package br.com.caixasimples.cadastro.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.internal.ClienteService.ClienteNaoEncontradoException;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolamento entre contas para {@code cliente} (RNF05), no mesmo molde de
 * {@code IsolamentoDeProdutoTest}: grava na conta A, consulta como conta B e espera vazio.
 *
 * <p>Vive no pacote {@code internal} porque precisa falar direto com {@code ClienteRepository}, que
 * é de visibilidade de pacote. É de propósito: se as afirmações passassem só pelo serviço, o teste
 * não distinguiria o filtro de {@code @TenantId} de um filtro escrito no caso de uso, e é o
 * {@code @TenantId} que se quer provar. Em nenhuma linha abaixo existe {@code WHERE conta_id}.
 */
class IsolamentoDeClienteTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ClienteService clienteService;

    @Autowired
    private ClienteRepository clientes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta B não enxerga cliente da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaClienteDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);

        UUID clienteDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Dona Marta", "(75) 99999-0000")));

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(clientes.findById(clienteDaContaA))
                    .as("findById atravessando tenant")
                    .isEmpty();
            assertThat(clientes.findAll())
                    .as("listagem da conta B")
                    .extracting(ClienteEntity::getId)
                    .doesNotContain(clienteDaContaA);
            assertThat(clientes.findByAtivoTrue())
                    .as("derived query da conta B")
                    .extracting(ClienteEntity::getId)
                    .doesNotContain(clienteDaContaA);
            assertThat(clienteService.listarAtivos())
                    .as("caso de uso da conta B")
                    .isEmpty();
        });

        // E a conta A continua vendo o próprio dado: o filtro não pode ser esconder de todos.
        TenantContext.executarComo(contaA.contaId(), () -> {
            assertThat(clientes.findById(clienteDaContaA)).isPresent();
            assertThat(clienteService.listarAtivos())
                    .singleElement()
                    .satisfies(cliente -> {
                        assertThat(cliente.id()).isEqualTo(clienteDaContaA);
                        assertThat(cliente.nome()).isEqualTo("Dona Marta");
                        assertThat(cliente.contato()).isEqualTo("(75) 99999-0000");
                    });
        });
    }

    @Test
    @DisplayName("caso de uso de outra conta não alcança o cliente nem para editar ou inativar")
    void casoDeUsoDeOutraContaNaoAlcancaOCliente() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);

        UUID clienteDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Seu Jose", null)));

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThatExceptionOfType(ClienteNaoEncontradoException.class)
                    .as("editar cliente de outra conta")
                    .isThrownBy(() -> clienteService.editar(clienteDaContaA,
                            new DadosDoCliente("Sequestrado", null)));
            assertThatExceptionOfType(ClienteNaoEncontradoException.class)
                    .as("inativar cliente de outra conta")
                    .isThrownBy(() -> clienteService.inativar(clienteDaContaA));
            assertThatExceptionOfType(ClienteNaoEncontradoException.class)
                    .as("reativar cliente de outra conta")
                    .isThrownBy(() -> clienteService.reativar(clienteDaContaA));
        });

        // Nada do que a conta B tentou tocou a linha da conta A.
        TenantContext.executarComo(contaA.contaId(), () ->
                assertThat(clienteService.listarAtivos())
                        .singleElement()
                        .satisfies(cliente -> assertThat(cliente.nome()).isEqualTo("Seu Jose")));
    }

    @Test
    @DisplayName("contaId de um cliente vem do contexto, nunca de parâmetro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Oficina Teste", SENHA_DE_TESTE);

        // Repare que nem o caso de uso nem a entidade recebem a conta: não existe assinatura por
        // onde um chamador pudesse informá-la (RNF05).
        UUID clienteId = TenantContext.executarComo(conta.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Cliente da oficina", null)));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clientes.findById(clienteId))
                        .get()
                        .extracting(ClienteEntity::getContaId)
                        .isEqualTo(conta.contaId()));
    }
}
