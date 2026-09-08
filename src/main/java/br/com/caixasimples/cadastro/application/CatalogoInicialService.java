package br.com.caixasimples.cadastro.application;

import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ModeloProdutoEntity;
import br.com.caixasimples.cadastro.internal.ModeloProdutoRepository;
import br.com.caixasimples.shared.Money;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copia o catalogo sugerido do tipo de negocio para o catalogo da conta (RF32) — passo R05 do
 * roteiro, etapa 1.3 do plano.
 *
 * <p><strong>Copia, nunca referencia ao vivo</strong>, e e essa escolha que sustenta o isolamento
 * apesar de {@code modelo_produto} ser uma tabela compartilhada: depois de copiado, o item e um
 * {@code Produto} da conta como qualquer outro — editavel, inativavel, invisivel para as demais.
 * Alterar o modelo depois nao alcanca ninguem que ja copiou.
 *
 * <p><strong>Ninguem chama este caso de uso ainda</strong> (D20d). O RF32 fala em primeiro acesso e
 * a A3 diz que isso e o primeiro login, mas nao existe tela; o gatilho nasce com o PWA, no R23.
 * Ate la o metodo e ponto de entrada unico e testado.
 *
 * <p>A copia passa por {@link ProdutoService#cadastrar}, e nao por um {@code save} direto: o item
 * copiado atravessa exatamente as mesmas regras do cadastro feito no balcao, e {@code produto} nao
 * ganha um segundo caminho de escrita para manter em dia. E o mesmo motivo pelo qual o seed da A3
 * roda pela aplicacao em vez de por SQL.
 */
@Service
public class CatalogoInicialService {

    private final ModeloProdutoRepository modelos;
    private final ProdutoService produtos;

    CatalogoInicialService(ModeloProdutoRepository modelos, ProdutoService produtos) {
        this.modelos = modelos;
        this.produtos = produtos;
    }

    /**
     * Copia para a conta do contexto atual todos os modelos do tipo de negocio informado (RF32).
     *
     * <p><strong>Copia sempre, a cada chamada</strong> (D20f): nao ha guarda de idempotencia. Uma
     * checagem de catalogo vazio escondida aqui dentro seria uma condicao que o nome do metodo nao
     * anuncia, e o gatilho que decidira <em>quando</em> chamar ainda nem existe. Chamar duas vezes
     * duplica o catalogo — quem ligar o gatilho no R23 decide a condicao.
     *
     * <p>O preco de cada item copiado nasce em <strong>zero</strong> (D20c), que a D16c ja aceita
     * como valido: preco e hiperlocal, e um numero sugerido pela plataforma seria chute sobre o
     * mercado do negocio. O que o RF32 poupa e a digitacao de nome, categoria e unidade.
     *
     * @param tipoNegocio o {@code tipo_negocio} da conta; casa ignorando maiuscula/minuscula
     *                    (D20e). Nulo, em branco ou sem modelo correspondente nao e erro: devolve
     *                    zero e o cadastro comeca em branco, como o modelo de dados §3 preve
     * @return quantos itens foram copiados
     */
    @Transactional
    public int aplicarPara(String tipoNegocio) {
        if (tipoNegocio == null || tipoNegocio.isBlank()) {
            return 0;
        }

        List<ModeloProdutoEntity> sugestoes = modelos.findByTipoNegocioIgnoreCase(tipoNegocio.trim());

        for (ModeloProdutoEntity modelo : sugestoes) {
            produtos.cadastrar(modelo.getTipo(), new DadosDoProduto(
                    modelo.getNome(),
                    Money.ZERO,
                    // Sem codigo, porque modelo_produto nao tem essa coluna (modelo de dados §3):
                    // codigo e a referencia interna de cada negocio (D11), nao da plataforma.
                    null,
                    modelo.getCategoria(),
                    modelo.getUnidade(),
                    modelo.getAtributosSugeridos()));
        }

        return sugestoes.size();
    }
}
