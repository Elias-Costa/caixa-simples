package br.com.caixasimples.cadastro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.Cliente;
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
 * Os casos de uso do cadastro de cliente contra o banco de verdade: cadastrar (RF03), editar
 * (RF04), inativar e reativar (RF05).
 *
 * <p>Fica fora do pacote {@code internal} de propósito: aqui só se enxerga o que um controller vai
 * enxergar. Se algum teste daqui precisasse da entidade ou do repositório, seria sinal de que o
 * slice não expõe o suficiente, ou de que expõe demais.
 */
class ClienteServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ClienteService clienteService;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("cadastrar grava o cliente e ele aparece na listagem ativa")
    void cadastrarGravaEListaOCliente() {
        ContaCriada conta = criador.criar("Cafeteria do Centro", SENHA_DE_TESTE);

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Dona Marta", "marta@exemplo.test")));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clienteService.listarAtivos())
                        .singleElement()
                        .satisfies(cliente -> {
                            assertThat(cliente.id()).isEqualTo(id);
                            assertThat(cliente.nome()).isEqualTo("Dona Marta");
                            assertThat(cliente.contato()).isEqualTo("marta@exemplo.test");
                        }));
    }

    @Test
    @DisplayName("cliente existe só com nome, e contato em branco vira ausente")
    void contatoEOpcional() {
        ContaCriada conta = criador.criar("Balcao Apressado", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () -> {
            clienteService.cadastrar(new DadosDoCliente("Cliente sem contato", null));
            clienteService.cadastrar(new DadosDoCliente("Cliente com contato em branco", "   "));
        });

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clienteService.listarAtivos())
                        .hasSize(2)
                        .allSatisfy(cliente -> assertThat(cliente.contato()).isNull()));
    }

    @Test
    @DisplayName("editar substitui nome e contato (RF04)")
    void editarSubstituiOsCampos() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Nome errado", "(75) 90000-0000")));

        TenantContext.executarComo(conta.contaId(), () ->
                clienteService.editar(id, new DadosDoCliente("Nome certo", "(75) 98888-1111")));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clienteService.listarAtivos())
                        .singleElement()
                        .isEqualTo(new Cliente(id, "Nome certo", "(75) 98888-1111")));
    }

    @Test
    @DisplayName("inativar tira o cliente da listagem e reativar traz de volta (RF05)")
    void inativarEReativar() {
        ContaCriada conta = criador.criar("Salao da Praca", SENHA_DE_TESTE);

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Cliente antigo", null)));

        TenantContext.executarComo(conta.contaId(), () -> clienteService.inativar(id));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clienteService.listarAtivos()).isEmpty());

        TenantContext.executarComo(conta.contaId(), () -> clienteService.reativar(id));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clienteService.listarAtivos())
                        .singleElement()
                        .extracting(Cliente::id)
                        .isEqualTo(id));
    }

    @Test
    @DisplayName("inativar e reativar são idempotentes")
    void inativarEReativarSaoIdempotentes() {
        ContaCriada conta = criador.criar("Mercadinho do Bairro", SENHA_DE_TESTE);

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Cliente repetido", null)));

        // Dois cliques no balcão, ou a requisição que o cliente offline reenvia ao voltar a rede.
        TenantContext.executarComo(conta.contaId(), () ->
                assertThatNoException().isThrownBy(() -> {
                    clienteService.inativar(id);
                    clienteService.inativar(id);
                    clienteService.reativar(id);
                    clienteService.reativar(id);
                }));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(clienteService.listarAtivos()).singleElement()
                        .extracting(Cliente::id)
                        .isEqualTo(id));
    }

    @Test
    @DisplayName("editar cliente inativo é recusado")
    void editarClienteInativoERecusado() {
        ContaCriada conta = criador.criar("Oficina da Avenida", SENHA_DE_TESTE);

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                clienteService.cadastrar(new DadosDoCliente("Cliente que saiu", null)));

        TenantContext.executarComo(conta.contaId(), () -> clienteService.inativar(id));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> clienteService.editar(id,
                                new DadosDoCliente("Nome novo", null))));
    }

    @Test
    @DisplayName("cliente sem nome é recusado, e id que não existe nesta conta também")
    void bordasDeEntrada() {
        ContaCriada conta = criador.criar("Padaria do Centro", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("nome em branco")
                    .isThrownBy(() -> clienteService.cadastrar(new DadosDoCliente("  ", null)));
            assertThatExceptionOfType(ClienteNaoEncontradoException.class)
                    .as("id que não existe")
                    .isThrownBy(() -> clienteService.inativar(UUID.randomUUID()));
        });
    }
}
