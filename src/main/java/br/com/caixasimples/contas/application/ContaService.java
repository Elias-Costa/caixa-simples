package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O que os outros módulos podem perguntar sobre a conta em operação.
 *
 * <p><strong>Nenhum método recebe a conta como parâmetro, e essa ausência é a regra.</strong>
 * {@code Conta} é a única entidade de negócio sem filtro automático de tenant, porque o id dela
 * <em>é</em> o tenant; em troca, toda leitura parte de {@code TenantContext.exigirAtual()} e
 * nunca de um id que alguém informe (RNF05). Quem precisa saber algo de outra conta não tem por
 * onde perguntar.
 *
 * <p>Quem chama daqui hoje é o módulo de estoque, que precisa saber se a conta ligou o controle
 * de estoque (RF17) antes de dar baixa numa venda concluída. É pergunta, não efeito colateral, e
 * por isso é chamada direta à API deste módulo.
 */
@Service
public class ContaService {

    private final ContaRepository contas;

    ContaService(ContaRepository contas) {
        this.contas = contas;
    }

    /**
     * Se a conta em operação ligou o controle de estoque (RF17). Conta nova nasce com ele
     * desligado, porque negócio baseado em serviço não precisa dele.
     *
     * @throws br.com.caixasimples.shared.TenantNaoResolvidoException se não há conta no contexto
     * @throws IllegalStateException se a conta do contexto não existe, o que só acontece com um
     *         evento que carrega uma conta apagada do banco por fora da aplicação
     */
    @Transactional(readOnly = true)
    public boolean estoqueHabilitado() {
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        return conta.isEstoqueHabilitado();
    }
}
