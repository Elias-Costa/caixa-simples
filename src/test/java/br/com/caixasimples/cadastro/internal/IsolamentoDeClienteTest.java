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
 * RNF05 para {@code cliente} — o passo 6 da skill {@code nova-entidade-multitenant}, no molde de
 * {@code IsolamentoDeProdutoTest}: grava na conta A, consulta como conta B, espera vazio.
 *
 * <p>Vive no pacote {@code internal} porque precisa falar direto com {@code ClienteRepository}, que
 * e de visibilidade de pacote. E de proposito: se as afirmacoes passassem so pelo servico, o teste
 * nao distinguiria filtro de {@code @TenantId} de filtro escrito no caso de uso — e e o
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
    @DisplayName("conta B nao enxerga cliente da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaClienteDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Piloto", SENHA_DE_TESTE);
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

        // E a conta A continua vendo o proprio dado — filtro nao pode ser esconde de todos.
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
    @DisplayName("caso de uso de outra conta nao alcanca o cliente nem para editar ou inativar")
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
    @DisplayName("contaId de um cliente vem do contexto, nunca de parametro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Oficina Teste", SENHA_DE_TESTE);

        // Repare que nem o caso de uso nem a entidade recebem a conta: nao existe assinatura por
        // onde um chamador pudesse informa-la (RNF05).
        UUID clienteId = TenantContext.executarComo(conta.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Cliente da oficina", null)));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clientes.findById(clienteId))
                        .get()
                        .extracting(ClienteEntity::getContaId)
                        .isEqualTo(conta.contaId()));
    }
}
