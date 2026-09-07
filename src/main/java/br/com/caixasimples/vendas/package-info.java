/**
 * Bounded Context de vendas (PDV): agregado {@code Venda} (raiz, com {@code ItemVenda} e
 * {@code Pagamento} como membros).
 *
 * <p>Concluir ou cancelar uma venda publica evento de dominio — este modulo nunca chama
 * {@code caixa} nem {@code estoque} diretamente para produzir efeito colateral.
 */
@ApplicationModule(displayName = "Vendas")
package br.com.caixasimples.vendas;

import org.springframework.modulith.ApplicationModule;
