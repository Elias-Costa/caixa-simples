package br.com.caixasimples.cadastro.application;

import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ModeloProdutoEntity;
import br.com.caixasimples.cadastro.internal.ModeloProdutoRepository;
import br.com.caixasimples.shared.Money;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copia o catálogo sugerido do tipo de negócio para o catálogo da conta (RF32).
 *
 * <p><strong>Copia, nunca referencia ao vivo</strong>, e é essa escolha que sustenta o isolamento
 * apesar de {@code modelo_produto} ser uma tabela compartilhada: depois de copiado, o item é um
 * {@code Produto} da conta como qualquer outro, editável, inativável e invisível para as demais.
 * Alterar o modelo depois não alcança ninguém que já copiou.
 *
 * <p><strong>Ainda não há chamador em produção.</strong> O RF32 fala em primeiro acesso, e o
 * primeiro acesso é o primeiro login, mas ainda não existe tela. Enquanto o gatilho não nasce, o
 * método é ponto de entrada único e testado.
 *
 * <p>A cópia passa por {@link ProdutoService#cadastrar}, e não por um {@code save} direto: o item
 * copiado atravessa exatamente as mesmas regras do cadastro feito no balcão, e {@code produto} não
 * ganha um segundo caminho de escrita para manter em dia. É o mesmo motivo pelo qual a criação de
 * conta roda pela aplicação em vez de por SQL.
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
     * Copia para a conta do contexto atual todos os modelos do tipo de negócio informado (RF32).
     *
     * <p><strong>Copia sempre, a cada chamada.</strong> Não há guarda de idempotência. Uma checagem
     * de catálogo vazio escondida aqui dentro seria uma condição que o nome do método não anuncia, e
     * o gatilho que decidirá <em>quando</em> chamar ainda nem existe. Chamar duas vezes duplica o
     * catálogo, e quem ligar o gatilho decide a condição.
     *
     * <p>O preço de cada item copiado nasce em <strong>zero</strong>, que o cadastro já aceita como
     * válido. Preço é hiperlocal, e um número sugerido pela plataforma seria chute sobre o mercado
     * daquele negócio. O que o RF32 poupa é a digitação de nome, categoria e unidade.
     *
     * @param tipoNegocio o {@code tipo_negocio} da conta; casa ignorando maiúscula e minúscula.
     *                    Nulo, em branco ou sem modelo correspondente não é erro: devolve zero e o
     *                    cadastro começa em branco
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
                    // Sem código, porque modelo_produto não tem essa coluna: código é a referência
                    // interna de cada negócio, não da plataforma.
                    null,
                    modelo.getCategoria(),
                    modelo.getUnidade(),
                    modelo.getAtributosSugeridos()));
        }

        return sugestoes.size();
    }
}
