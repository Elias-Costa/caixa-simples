import 'fake-indexeddb/auto'
import { afterEach, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import type { Identidade } from './Identidade'
import { prepararCadastroLocal } from './prepararCadastroLocal'

const ana: Identidade = {
  usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta-a',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

afterEach(() => vi.restoreAllMocks())

function espionarCadastro() {
  return [
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([]),
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([]),
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([]),
  ]
}

it('com a senha provisória não pede nada: o servidor recusaria cada pedido', async () => {
  const espioes = espionarCadastro()

  await prepararCadastroLocal({ ...ana, senhaProvisoria: true })

  for (const espiao of espioes) expect(espiao).not.toHaveBeenCalled()
})

it('com a senha escolhida, guarda produtos e clientes no aparelho', async () => {
  const espioes = espionarCadastro()

  await prepararCadastroLocal({ ...ana, senhaProvisoria: false })

  for (const espiao of espioes) expect(espiao).toHaveBeenCalledOnce()
})
