package br.com.caixasimples.cadastro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * D11 — codigo do produto (RF06). O indice unico e <strong>parcial</strong> e sobre
 * {@code lower(codigo)}, entao tem tres comportamentos que so o banco garante e que este teste
 * fixa: caixa nao distingue, a unicidade e por conta, e produto inativo nao ocupa codigo.
 */
class CodigoDeProdutoTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    private static Produto comCodigo(String nome, String codigo) {
        return new Produto(nome, Money.de("10.00"), TipoProduto.PRODUTO, codigo, null, null,
                Map.of());
    }

    @Test
    @DisplayName("ABC-12 e abc-12 sao o mesmo codigo dentro da conta")
    void codigoNaoDistingueCaixa() {
        ContaCriada conta = criador.criar("Loja com Codigo", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () ->
                produtos.save(ProdutoEntity.de(comCodigo("Primeiro", "ABC-12"))));

        // O operador digita rapido no balcao; a venda nao pode depender de maiuscula.
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        produtos.save(ProdutoEntity.de(comCodigo("Segundo", "abc-12")))));
    }

    @Test
    @DisplayName("a unicidade e por conta, nunca global")
    void contasDiferentesPodemUsarOMesmoCodigo() {
        ContaCriada contaA = criador.criar("Negocio A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Negocio B", SENHA_DE_TESTE);

        TenantContext.executarComo(contaA.contaId(), () ->
                produtos.save(ProdutoEntity.de(comCodigo("Item da A", "REF-1"))));

        assertThatNoException().isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        produtos.save(ProdutoEntity.de(comCodigo("Item da B", "REF-1")))));
    }

    @Test
    @DisplayName("produto inativo nao ocupa codigo — o numero pode ser reaproveitado")
    void inativoLiberaOCodigo() {
        ContaCriada conta = criador.criar("Loja que Renova Catalogo", SENHA_DE_TESTE);

        // O soft delete mantem o registro para sempre; unicidade global "queimaria" um codigo a
        // cada item que sai de linha, o que e inviavel em catalogo pequeno.
        TenantContext.executarComo(conta.contaId(), () -> {
            Produto saiuDeLinha = comCodigo("Modelo antigo", "REF-9");
            saiuDeLinha.inativar();
            produtos.save(ProdutoEntity.de(saiuDeLinha));

            Produto tambemSaiu = comCodigo("Outro modelo antigo", "REF-9");
            tambemSaiu.inativar();
            produtos.save(ProdutoEntity.de(tambemSaiu));

            return produtos.save(ProdutoEntity.de(comCodigo("Modelo novo", "REF-9")));
        });

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(produtos.findByAtivoTrue())
                        .as("so o modelo novo esta ativo com o codigo REF-9")
                        .hasSize(1));
    }

    @Test
    @DisplayName("codigo e opcional: varios produtos sem codigo convivem")
    void codigoEhOpcional() {
        ContaCriada conta = criador.criar("Salao sem Codigo", SENHA_DE_TESTE);

        // No Postgres um indice unico aceita varios NULL, entao isso funciona sem caso especial —
        // e cobre tambem o produto vindo do catalogo inicial do RF32, que nasce sem codigo.
        assertThatNoException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () -> {
                    produtos.save(ProdutoEntity.de(comCodigo("Corte", null)));
                    produtos.save(ProdutoEntity.de(comCodigo("Escova", null)));
                    // Texto em branco vira ausencia, e nao um codigo vazio disputando o indice.
                    return produtos.save(ProdutoEntity.de(comCodigo("Hidratacao", "   ")));
                }));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(produtos.findByAtivoTrue()).hasSize(3));
    }
}
