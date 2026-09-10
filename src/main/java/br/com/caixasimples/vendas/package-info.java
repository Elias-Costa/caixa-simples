/**
 * Bounded Context de vendas: agregado {@code Venda}, raiz que tem {@code ItemVenda} e
 * {@code Pagamento} como membros.
 *
 * <p>Concluir ou cancelar uma venda publica evento de domínio. Este módulo nunca chama
 * {@code caixa} nem {@code estoque} diretamente para produzir efeito colateral.
 */
@ApplicationModule(displayName = "Vendas")
package br.com.caixasimples.vendas;

import org.springframework.modulith.ApplicationModule;
