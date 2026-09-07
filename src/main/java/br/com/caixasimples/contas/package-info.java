/**
 * Bounded Context de identidade e tenancy: {@code Conta} (o negocio contratante, isto e, o
 * tenant), {@code Usuario}, autenticacao e plano contratado.
 *
 * <p>Cobre a area funcional do escopo §4.7 e dos requisitos §3.7 — RF28 (isolamento), RF29/RF30
 * (perfis Administrador e Operador), RF31 (troca de plano) e RNF06 (autenticacao) — que a
 * arquitetura §2 nao incluiu na lista de pacotes. Decisao D4 do plano de implementacao.
 *
 * <p>Este e o unico modulo cuja raiz de agregado ({@code Conta}) nao carrega {@code @TenantId}:
 * o {@code id} dela <em>e</em> o tenant. Ver {@code .claude/rules/multi-tenancy.md}.
 */
@ApplicationModule(displayName = "Contas")
package br.com.caixasimples.contas;

import org.springframework.modulith.ApplicationModule;
