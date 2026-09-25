/**
 * Sincronização em lote dos gestos que o dispositivo registrou sem rede (RNF01 a RNF03).
 *
 * <p>O dispositivo grava cada gesto numa fila local, com um id de operação, o registro que ele cria
 * ou altera, o conteúdo, a versão que leu e as operações de que depende. Quando a rede volta, envia
 * a fila em lotes. Este módulo recebe o lote, aplica cada operação na ordem das dependências, numa
 * transação por operação, e grava o resultado na mesma transação do efeito. Reenviar o mesmo lote,
 * inclusive depois de uma resposta perdida, devolve o resultado gravado em vez de aplicar de novo.
 *
 * <p><strong>Este módulo não sabe aplicar gesto nenhum.</strong> Ele declara a porta
 * {@link br.com.caixasimples.sincronizacao.AplicadorDeOperacoes}, e cada módulo dono implementa a
 * dele no próprio {@code internal}, chamando os próprios casos de uso: o cadastro aplica os gestos
 * de Produto e Cliente, o caixa os da SessaoCaixa e vendas os da Venda. As regras continuam nas
 * raízes e nos casos de uso de sempre. Grafo resultante: cadastro, caixa e vendas dependem deste
 * módulo; ele depende só de shared.
 */
@ApplicationModule(displayName = "Sincronizacao")
package br.com.caixasimples.sincronizacao;

import org.springframework.modulith.ApplicationModule;
