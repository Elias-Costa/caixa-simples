package br.com.caixasimples.sincronizacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Os gestos que o aparelho registra sem rede, fixados um a um.
 *
 * <p>Nenhum deles é recurso pago: produto, cliente, caixa e Venda funcionam em qualquer plano,
 * inclusive suspenso. Por isso o lote aplica todos sem perguntar o plano. Um gesto novo, de
 * estoque ou de outro recurso pago, quebra este teste de propósito: antes de entrar na lista, quem
 * o cria decide o que acontece com ele quando o plano não o inclui ou está suspenso. A regra
 * combinada para esse caso é mandar o gesto para revisão sem aplicar, e o administrador decide o
 * reenvio depois de regularizar.
 */
class GestosForaDoPlanoTest extends TesteDeIntegracao {

    @Autowired
    private List<AplicadorDeOperacoes> aplicadores;

    @Test
    @DisplayName("nenhum gesto offline é recurso pago; gesto novo obriga a decidir o que a suspensão faz com ele")
    void gestosDeHojeNaoSaoRecursoPago() {
        Set<String> tipos = aplicadores.stream()
                .flatMap(aplicador -> aplicador.tipos().stream())
                .collect(Collectors.toSet());

        assertThat(tipos).containsExactlyInAnyOrder(
                "produto.criar", "produto.editar", "produto.inativar",
                "cliente.criar", "cliente.editar", "cliente.inativar", "cliente.reativar",
                "caixa.abrir", "caixa.sangrar", "caixa.suprir", "caixa.fechar",
                "venda.iniciar", "venda.adicionarItem", "venda.removerItem",
                "venda.aplicarDesconto", "venda.vincularCliente", "venda.registrarPagamento",
                "venda.concluir");
    }
}
