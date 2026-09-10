/**
 * Bounded Context de identidade e tenancy: {@code Conta}, que é o negócio contratante e portanto o
 * tenant, {@code Usuario}, autenticação e plano contratado.
 *
 * <p>Cobre o isolamento entre contas (RF28), os perfis de Administrador e Operador (RF29, RF30), a
 * troca de plano (RF31) e a autenticação (RNF06).
 *
 * <p>Este é o único módulo cuja raiz de agregado, {@code Conta}, não carrega {@code @TenantId}: o
 * {@code id} dela <em>é</em> o tenant, então não há o que filtrar.
 */
@ApplicationModule(displayName = "Contas")
package br.com.caixasimples.contas;

import org.springframework.modulith.ApplicationModule;
