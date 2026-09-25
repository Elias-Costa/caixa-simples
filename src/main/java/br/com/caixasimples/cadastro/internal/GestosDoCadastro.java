package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService.ClienteNaoEncontradoException;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.sincronizacao.Aplicacao;
import br.com.caixasimples.sincronizacao.AplicadorDeOperacoes;
import br.com.caixasimples.sincronizacao.OperacaoRecebida;
import br.com.caixasimples.sincronizacao.OperacaoRecusadaException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Os gestos de Produto e de Cliente que o dispositivo registrou sem rede, aplicados pelos casos de
 * uso do cadastro.
 *
 * <p>Cada gesto é o caso de uso com rede de mesmo nome, com as mesmas guardas: só o administrador
 * cadastra, edita e inativa produto; cliente é dos dois perfis. A criação usa o id e o instante que
 * o dispositivo gravou, porque a Venda registrada sem rede já aponta para esse id. A edição segue a
 * última escrita aceita pelo servidor: é aplicada sobre a versão atual, qualquer que seja a que o
 * dispositivo leu, e a revisão nova volta para ele.
 *
 * <p>Produto ou Cliente que não existe nesta conta é recusa do gesto, e não defeito.
 */
@Component
class GestosDoCadastro implements AplicadorDeOperacoes {

    private final ProdutoService produtos;
    private final ClienteService clientes;
    private final ProdutoRepository linhasDeProduto;
    private final ClienteRepository linhasDeCliente;
    private final ObjectMapper json;

    GestosDoCadastro(ProdutoService produtos, ClienteService clientes,
            ProdutoRepository linhasDeProduto, ClienteRepository linhasDeCliente,
            ObjectMapper json) {
        this.produtos = produtos;
        this.clientes = clientes;
        this.linhasDeProduto = linhasDeProduto;
        this.linhasDeCliente = linhasDeCliente;
        this.json = json;
    }

    @Override
    public Set<String> tipos() {
        return Set.of("produto.criar", "produto.editar", "produto.inativar", "cliente.criar",
                "cliente.editar", "cliente.inativar", "cliente.reativar");
    }

    @Override
    public Aplicacao aplicar(OperacaoRecebida operacao) {
        UUID id = operacao.registroId();
        try {
            switch (operacao.tipo()) {
                case "produto.criar" -> {
                    CriacaoDeProduto criacao = operacao.payloadComo(json, CriacaoDeProduto.class);
                    produtos.cadastrar(id, operacao.exigir(criacao.tipo(), "tipo"),
                            criacao.dados(operacao), operacao.criadoEm());
                }
                case "produto.editar" -> produtos.editar(id,
                        operacao.payloadComo(json, EdicaoDeProduto.class).dados(operacao));
                case "produto.inativar" -> produtos.inativar(id);
                case "cliente.criar" -> clientes.cadastrar(id,
                        operacao.payloadComo(json, CamposDoCliente.class).dados(),
                        operacao.criadoEm());
                case "cliente.editar" -> clientes.editar(id,
                        operacao.payloadComo(json, CamposDoCliente.class).dados());
                case "cliente.inativar" -> clientes.inativar(id);
                case "cliente.reativar" -> clientes.reativar(id);
                default -> throw new OperacaoRecusadaException(
                        "gesto de cadastro desconhecido: " + operacao.tipo());
            }
        } catch (ProdutoNaoEncontradoException | ClienteNaoEncontradoException naoEncontrado) {
            throw new OperacaoRecusadaException(naoEncontrado.getMessage(), naoEncontrado);
        }
        return Aplicacao.aplicada(versaoDepoisDe(operacao));
    }

    /**
     * A revisão só muda quando a alteração vai ao banco, então a leitura vem depois de enviar o que
     * está pendente; sem isso, voltaria a revisão de antes do gesto.
     */
    private Long versaoDepoisDe(OperacaoRecebida operacao) {
        if (operacao.tipo().startsWith("produto.")) {
            linhasDeProduto.flush();
            return linhasDeProduto.findById(operacao.registroId()).orElseThrow().getVersao();
        }
        linhasDeCliente.flush();
        return linhasDeCliente.findById(operacao.registroId()).orElseThrow().getVersao();
    }

    /** O conteúdo de {@code produto.criar}, como o dispositivo o grava. */
    record CriacaoDeProduto(TipoProduto tipo, String nome, BigDecimal preco, String codigo,
            String categoria, String unidade, Map<String, Object> atributos) {

        DadosDoProduto dados(OperacaoRecebida operacao) {
            return new DadosDoProduto(nome, operacao.dinheiro(preco, "preco"), codigo, categoria,
                    unidade, atributos);
        }
    }

    /** O conteúdo de {@code produto.editar}: sem tipo, que não muda depois do cadastro. */
    record EdicaoDeProduto(String nome, BigDecimal preco, String codigo, String categoria,
            String unidade, Map<String, Object> atributos) {

        DadosDoProduto dados(OperacaoRecebida operacao) {
            return new DadosDoProduto(nome, operacao.dinheiro(preco, "preco"), codigo, categoria,
                    unidade, atributos);
        }
    }

    /** O conteúdo de {@code cliente.criar} e de {@code cliente.editar}. */
    record CamposDoCliente(String nome, String contato) {

        DadosDoCliente dados() {
            return new DadosDoCliente(nome, contato);
        }
    }
}
