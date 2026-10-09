package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.application.PedidoDePlanoNaoEncontradoException;
import br.com.caixasimples.contas.application.PlanoService;
import br.com.caixasimples.contas.application.PlanoService.EstadoDoPlano;
import br.com.caixasimples.contas.application.PlanoService.PedidoNaConta;
import br.com.caixasimples.contas.internal.AssinaturaDePedido;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.PedidoDePlano;
import br.com.caixasimples.contas.internal.PedidoDePlanoRepository;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolamento entre contas (RNF05), o requisito que não admite grau.
 *
 * <p>Este teste é o molde para todo dado novo do sistema: grava na conta A, consulta como conta B e
 * espera vazio. Nenhuma entidade nova fecha sem o equivalente dele.
 *
 * <p>Prova por construção que o filtro é do Hibernate e não do código: em nenhum ponto abaixo
 * existe {@code WHERE conta_id = ?}, e ainda assim a conta B não vê nada da conta A.
 */
class IsolamentoEntreContasTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private CredencialRepository credenciais;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private PedidoDePlanoRepository pedidos;

    @Autowired
    private PlanoService planos;

    @Autowired
    private AssinaturaDePedido assinatura;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta B não enxerga usuário da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaUsuarioDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);

        contaB.comoUsuario(() -> {
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

        // E a conta A continua vendo o próprio dado: o filtro não pode ser esconder de todos.
        contaA.comoUsuario(() -> {
            assertThat(usuarios.findById(contaA.usuarioId())).isPresent();
            assertThat(usuarios.findAll())
                    .extracting(Usuario::getId)
                    .containsExactly(contaA.usuarioId());
        });
    }

    @Test
    @DisplayName("conta B não enxerga nem aplica o pedido de plano da conta A, nem com o código certo, e o pedido dela não toca o da A")
    void contaNaoEnxergaPedidoDePlanoDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);
        PedidoNaConta pedidoNaA = contaA.comoUsuario(() -> planos.pedir(Plano.COMPLETO));
        UUID pedidoDeA = pedidoNaA.id();
        String codigoDeA = assinatura.codigo(pedidoDeA, Plano.COMPLETO, pedidoNaA.valor());

        contaB.comoUsuario(() -> {
            assertThat(pedidos.findById(pedidoDeA)).as("findById atravessando tenant").isEmpty();
            assertThat(pedidos.findAll()).as("listagem da conta B").isEmpty();
            assertThat(pedidos.findBySituacao(PedidoDePlano.Situacao.ABERTO))
                    .as("derived query da conta B")
                    .isEmpty();
            assertThat(planos.consultar().pedidoAberto()).isNull();
            assertThatExceptionOfType(PedidoDePlanoNaoEncontradoException.class)
                    .isThrownBy(() -> planos.aplicarCodigo(pedidoDeA, codigoDeA));
            assertThat(planos.consultar().plano()).isEqualTo(Plano.GRATIS);
        });

        contaA.comoUsuario(() -> {
            assertThat(pedidos.findById(pedidoDeA)).isPresent();
            assertThat(planos.consultar().plano()).as("o código não foi gasto pela outra conta")
                    .isEqualTo(Plano.GRATIS);
        });

        // A conta B pede o próprio plano: o pedido dela não substitui o aberto da A nem colide com
        // ele, e o código já entregue à A continua valendo.
        UUID pedidoDeB = contaB.comoUsuario(() -> planos.pedir(Plano.CAIXA_SIMPLES)).id();
        assertThat(pedidoDeB).isNotEqualTo(pedidoDeA);
        contaA.comoUsuario(() -> {
            assertThat(planos.consultar().pedidoAberto())
                    .as("o aberto da conta A continua o mesmo")
                    .extracting(PedidoNaConta::id)
                    .isEqualTo(pedidoDeA);
            assertThat(planos.aplicarCodigo(pedidoDeA, codigoDeA).plano())
                    .isEqualTo(Plano.COMPLETO);
        });
        contaB.comoUsuario(() -> {
            EstadoDoPlano estadoDeB = planos.consultar();
            assertThat(estadoDeB.plano()).isEqualTo(Plano.GRATIS);
            assertThat(estadoDeB.pedidoAberto()).extracting(PedidoNaConta::id)
                    .isEqualTo(pedidoDeB);
        });
    }

    @Test
    @DisplayName("contaId de um usuário vem do contexto, nunca de parâmetro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Loja Teste", SENHA_DE_TESTE);

        conta.comoUsuario(() ->
                assertThat(usuarios.findById(conta.usuarioId()))
                        .get()
                        .extracting(Usuario::getContaId)
                        .isEqualTo(conta.contaId()));
    }

    @Test
    @DisplayName("credencial é consultável sem tenant e resolve para a conta certa")
    void credencialResolveAContaSemTenantNoContexto() {
        ContaCriada contaA = criador.criar("Negocio A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Negocio B", SENHA_DE_TESTE);

        // Sem nenhum tenant no contexto, que é exatamente a situação do login, e o motivo de a
        // credencial não ter a anotação de tenant.
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
