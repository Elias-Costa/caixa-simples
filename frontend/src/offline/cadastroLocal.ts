import type { Cliente, DadosDoCliente, DadosDoProduto, Produto } from '../api/cadastro'
import { SemConexao } from '../api/cliente'
import { lerIdentidade } from '../sessao/armazenamento'
import {
  enfileirarGesto, gestoAplicavel, gestoPendente, guardarRetrato, guardarRetratos, jaEstaNoRetrato,
  lerDoServidor, lerRetrato, listarGestos, ordenarPorDependencia, versaoDoResultado,
  type GestoNaFila, type Retrato, type ValorJson,
} from './fila'

type Remoto = {
  produtos(): Promise<Produto[]>
  buscarProdutos(termo: string): Promise<Produto[]>
  criarProduto(dados: DadosDoProduto): Promise<{ id: string }>
  editarProduto(id: string, dados: DadosDoProduto): Promise<void>
  inativarProduto(id: string): Promise<void>
  clientes(): Promise<Cliente[]>
  clientesInativos(): Promise<Cliente[]>
  criarCliente(dados: DadosDoCliente): Promise<{ id: string }>
  editarCliente(id: string, dados: DadosDoCliente): Promise<void>
  inativarCliente(id: string): Promise<void>
  reativarCliente(id: string): Promise<void>
}

type ClienteLocal = Cliente & { ativo: boolean }
const GESTOS_PRODUTO = 'produto.'
const GESTOS_CLIENTE = 'cliente.'

function exigirAdmin(): void {
  if (lerIdentidade()?.perfil !== 'ADMIN') throw new Error('Só ADMIN pode alterar produtos.')
}

function validarProduto(dados: DadosDoProduto): void {
  if (!dados.nome.trim() || !Number.isFinite(dados.preco) || dados.preco < 0) {
    throw new Error('Informe nome e preço válido para o item.')
  }
}

function validarCliente(dados: DadosDoCliente): void {
  if (!dados.nome.trim()) throw new Error('Informe o nome do cliente.')
}

function payload(dados: unknown): ValorJson {
  return JSON.parse(JSON.stringify(dados)) as ValorJson
}

function camposDoProduto(dados: DadosDoProduto): DadosDoProduto {
  return {
    tipo: dados.tipo, nome: dados.nome, preco: dados.preco, codigo: dados.codigo,
    categoria: dados.categoria, unidade: dados.unidade, atributos: dados.atributos,
  }
}

function camposDeEdicaoDoProduto(dados: DadosDoProduto): Omit<DadosDoProduto, 'tipo'> {
  return {
    nome: dados.nome, preco: dados.preco, codigo: dados.codigo,
    categoria: dados.categoria, unidade: dados.unidade, atributos: dados.atributos,
  }
}

function camposDoCliente(dados: DadosDoCliente): DadosDoCliente {
  return { nome: dados.nome, contato: dados.contato }
}

function versaoLida(item: { versao?: number | null }): number {
  if (typeof item.versao !== 'number') {
    throw new Error('A revisão deste cadastro precisa ser carregada online antes da alteração.')
  }
  return item.versao
}

function exigirArmazenamento(): void {
  if (!('indexedDB' in globalThis)) throw new Error('O armazenamento local não está disponível.')
}

function gestosAplicaveis(gestos: GestoNaFila[], prefixo: string): GestoNaFila[] {
  return ordenarPorDependencia(gestos.filter((gesto) => gesto.tipo.startsWith(prefixo) && gestoAplicavel(gesto)))
}

/**
 * A mesma regra da busca do servidor no balcão (RF06): o termo é aparado e em branco é recusado; o
 * item cujo código é igual ao termo vem primeiro, depois os que têm o termo no nome, em ordem
 * alfabética, sem repetir. Maiúsculas não contam. Código parcial não entra, porque o código é único
 * e um prefixo devolveria mais de um item para um valor que se lê inteiro da embalagem.
 */
function buscarNoCatalogo(produtos: Produto[], termo: string): Produto[] {
  const alvo = termo.trim().toLocaleLowerCase('pt-BR')
  if (!alvo) throw new Error('Informe nome ou código para buscar.')
  const porCodigo = produtos.filter((item) => item.codigo?.trim().toLocaleLowerCase('pt-BR') === alvo)
  const porNome = produtos
    .filter((item) => !porCodigo.includes(item) && item.nome.toLocaleLowerCase('pt-BR').includes(alvo))
    .sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'))
  return [...porCodigo, ...porNome]
}

function ultimoGesto(gestos: GestoNaFila[], prefixo: string, id: string): GestoNaFila | undefined {
  return gestosAplicaveis(gestos, prefixo).filter((gesto) => gesto.registroId === id).at(-1)
}

/**
 * O retrato mais os gestos que ele ainda não contém. O gesto cujo resultado chegou antes da
 * leitura já está no retrato, e reaplicá-lo esconderia a alteração feita depois no servidor, como
 * o preço trocado em outro aparelho. O confirmado depois da leitura entra com a revisão que o
 * servidor devolveu.
 */
function projetarProdutos(retrato: Retrato<Produto[]> | null, gestos: GestoNaFila[]): Produto[] {
  const itens = new Map((retrato?.dados ?? []).map((item) => [item.id, item]))
  for (const gesto of gestosAplicaveis(gestos, GESTOS_PRODUTO)) {
    if (jaEstaNoRetrato(gesto, retrato)) continue
    const dados = gesto.payload as DadosDoProduto
    if (gesto.tipo === 'produto.criar') {
      itens.set(gesto.registroId, { id: gesto.registroId, versao: versaoDoResultado(gesto) ?? 0, ...dados })
    }
    if (gesto.tipo === 'produto.editar') {
      const anterior = itens.get(gesto.registroId)
      if (anterior) itens.set(gesto.registroId, { ...anterior, ...dados, tipo: anterior.tipo,
        versao: versaoDoResultado(gesto) ?? anterior.versao })
    }
    if (gesto.tipo === 'produto.inativar') itens.delete(gesto.registroId)
  }
  return [...itens.values()]
}

/** As duas listas de clientes são lidas e gravadas juntas, com a mesma ordem da leitura. */
function projetarClientes(ativos: Retrato<Cliente[]> | null, inativos: Retrato<Cliente[]> | null,
  gestos: GestoNaFila[]): ClienteLocal[] {
  const itens = new Map<string, ClienteLocal>([
    ...(ativos?.dados ?? []).map((item) => [item.id, { ...item, ativo: true }] as const),
    ...(inativos?.dados ?? []).map((item) => [item.id, { ...item, ativo: false }] as const),
  ])
  const leitura = ativos && inativos
    ? { ordemDaLeitura: Math.min(ativos.ordemDaLeitura, inativos.ordemDaLeitura) }
    : ativos ?? inativos
  for (const gesto of gestosAplicaveis(gestos, GESTOS_CLIENTE)) {
    if (jaEstaNoRetrato(gesto, leitura)) continue
    const dados = gesto.payload as DadosDoCliente
    if (gesto.tipo === 'cliente.criar') {
      itens.set(gesto.registroId, { id: gesto.registroId, versao: versaoDoResultado(gesto) ?? 0, ...dados,
        ativo: true })
    }
    const anterior = itens.get(gesto.registroId)
    if (!anterior) continue
    if (gesto.tipo === 'cliente.editar') {
      itens.set(gesto.registroId, { ...anterior, ...dados, versao: versaoDoResultado(gesto) ?? anterior.versao })
    }
    if (gesto.tipo === 'cliente.inativar' || gesto.tipo === 'cliente.reativar') {
      itens.set(gesto.registroId, { ...anterior, ativo: gesto.tipo === 'cliente.reativar',
        versao: versaoDoResultado(gesto) ?? anterior.versao })
    }
  }
  return [...itens.values()]
}

async function locaisProdutos(): Promise<Produto[]> {
  const [retrato, gestos] = await Promise.all([lerRetrato<Produto[]>('produtos'), listarGestos()])
  if (!retrato && !gestos.some((gesto) => gesto.tipo === 'produto.criar')) {
    throw new Error('O catálogo ainda não foi carregado neste dispositivo. Entre online para prepará-lo.')
  }
  return projetarProdutos(retrato, gestos)
}

async function locaisClientes(): Promise<ClienteLocal[]> {
  const [ativos, inativos, gestos] = await Promise.all([
    lerRetrato<Cliente[]>('clientesAtivos'), lerRetrato<Cliente[]>('clientesInativos'), listarGestos(),
  ])
  if (!ativos && !inativos && !gestos.some((gesto) => gesto.tipo === 'cliente.criar')) {
    throw new Error('O cadastro de clientes ainda não foi carregado neste dispositivo. Entre online para prepará-lo.')
  }
  return projetarClientes(ativos, inativos, gestos)
}

async function dependencias(prefixo: string, id: string): Promise<{ dependeDe: string[]; pendente: boolean }> {
  const ultimo = ultimoGesto(await listarGestos(), prefixo, id)
  return { dependeDe: ultimo ? [ultimo.operacaoId] : [], pendente: !!ultimo && gestoPendente(ultimo) }
}

async function enfileirar(tipo: string, id: string, dados: unknown, dependeDe: string[] = [], versaoBase?: number): Promise<void> {
  await enfileirarGesto({ tipo, registroId: id, payload: payload(dados), dependeDe, versaoBase })
}

/** A lista da API é a base local; gestos da sessão são reaplicados sem copiar o catálogo de novo. */
export function criarCadastroLocal<T extends Remoto>(remoto: T): T {
  let leituraDosClientes: Promise<unknown> | null = null

  // As duas listas vêm juntas para o retrato ter uma ordem só, e a tela que pede as duas ao mesmo
  // tempo aproveita o mesmo pedido.
  function lerClientesDoServidor(): Promise<unknown> {
    leituraDosClientes ??= lerDoServidor(
      () => Promise.all([remoto.clientes(), remoto.clientesInativos()]),
      ([ativos, inativos], ordem) => guardarRetratos([
        { tipo: 'clientesAtivos', dados: ativos }, { tipo: 'clientesInativos', dados: inativos },
      ], ordem),
    ).finally(() => { leituraDosClientes = null })
    return leituraDosClientes
  }

  return {
    ...remoto,
    async produtos() {
      if (!('indexedDB' in globalThis)) return remoto.produtos()
      if (navigator.onLine) {
        try {
          await lerDoServidor(() => remoto.produtos(),
            (recebidos, ordem) => guardarRetrato('produtos', recebidos, ordem))
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      return locaisProdutos()
    },
    async buscarProdutos(termo) {
      if (!('indexedDB' in globalThis)) return remoto.buscarProdutos(termo)
      // Com item guardado só no dispositivo, a busca da API não o acharia e o preço dela ignoraria
      // a edição que ainda não chegou ao servidor.
      const produtoPendente = (await listarGestos()).some((gesto) =>
        gesto.tipo.startsWith(GESTOS_PRODUTO) && gestoPendente(gesto))
      if (navigator.onLine && !produtoPendente) {
        try {
          return await remoto.buscarProdutos(termo)
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      return buscarNoCatalogo(await locaisProdutos(), termo)
    },
    async clientes() {
      if (!('indexedDB' in globalThis)) return remoto.clientes()
      if (navigator.onLine) {
        try {
          await lerClientesDoServidor()
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      return (await locaisClientes()).filter((cliente) => cliente.ativo)
    },
    async clientesInativos() {
      if (!('indexedDB' in globalThis)) return remoto.clientesInativos()
      if (navigator.onLine) {
        try {
          await lerClientesDoServidor()
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      return (await locaisClientes()).filter((cliente) => !cliente.ativo)
    },
    async criarProduto(dados) {
      if (navigator.onLine) return remoto.criarProduto(dados)
      exigirArmazenamento()
      exigirAdmin()
      validarProduto(dados)
      const codigo = dados.codigo?.trim().toLocaleLowerCase()
      if (codigo && (await locaisProdutos()).some((item) => item.codigo?.toLocaleLowerCase() === codigo)) {
        throw new Error('Já existe item ativo com esse código neste dispositivo.')
      }
      const id = crypto.randomUUID()
      await enfileirar('produto.criar', id, camposDoProduto(dados))
      return { id }
    },
    async editarProduto(id, dados) {
      if (navigator.onLine && !('indexedDB' in globalThis)) return remoto.editarProduto(id, dados)
      exigirArmazenamento()
      const { dependeDe, pendente } = await dependencias(GESTOS_PRODUTO, id)
      if (navigator.onLine && !pendente) return remoto.editarProduto(id, dados)
      exigirAdmin()
      validarProduto(dados)
      const itens = await locaisProdutos()
      const atual = itens.find((item) => item.id === id)
      if (!atual) throw new Error('Item não encontrado no catálogo local.')
      const codigo = dados.codigo?.trim().toLocaleLowerCase()
      if (codigo && itens.some((item) => item.id !== id && item.codigo?.toLocaleLowerCase() === codigo)) {
        throw new Error('Já existe item ativo com esse código neste dispositivo.')
      }
      await enfileirar('produto.editar', id, camposDeEdicaoDoProduto(dados), dependeDe, versaoLida(atual))
    },
    async inativarProduto(id) {
      if (navigator.onLine && !('indexedDB' in globalThis)) return remoto.inativarProduto(id)
      exigirArmazenamento()
      const { dependeDe, pendente } = await dependencias(GESTOS_PRODUTO, id)
      if (navigator.onLine && !pendente) return remoto.inativarProduto(id)
      exigirAdmin()
      const atual = (await locaisProdutos()).find((item) => item.id === id)
      if (!atual) throw new Error('Item não encontrado no catálogo local.')
      await enfileirar('produto.inativar', id, {}, dependeDe, versaoLida(atual))
    },
    async criarCliente(dados) {
      if (navigator.onLine) return remoto.criarCliente(dados)
      exigirArmazenamento()
      validarCliente(dados)
      const id = crypto.randomUUID()
      await enfileirar('cliente.criar', id, camposDoCliente(dados))
      return { id }
    },
    async editarCliente(id, dados) {
      if (navigator.onLine && !('indexedDB' in globalThis)) return remoto.editarCliente(id, dados)
      exigirArmazenamento()
      const { dependeDe, pendente } = await dependencias(GESTOS_CLIENTE, id)
      if (navigator.onLine && !pendente) return remoto.editarCliente(id, dados)
      validarCliente(dados)
      const atual = (await locaisClientes()).find((item) => item.id === id && item.ativo)
      if (!atual) throw new Error('Cliente ativo não encontrado no cadastro local.')
      await enfileirar('cliente.editar', id, camposDoCliente(dados), dependeDe, versaoLida(atual))
    },
    async inativarCliente(id) {
      if (navigator.onLine && !('indexedDB' in globalThis)) return remoto.inativarCliente(id)
      exigirArmazenamento()
      const { dependeDe, pendente } = await dependencias(GESTOS_CLIENTE, id)
      if (navigator.onLine && !pendente) return remoto.inativarCliente(id)
      const atual = (await locaisClientes()).find((item) => item.id === id)
      if (!atual) throw new Error('Cliente não encontrado no cadastro local.')
      await enfileirar('cliente.inativar', id, {}, dependeDe, versaoLida(atual))
    },
    async reativarCliente(id) {
      if (navigator.onLine && !('indexedDB' in globalThis)) return remoto.reativarCliente(id)
      exigirArmazenamento()
      const { dependeDe, pendente } = await dependencias(GESTOS_CLIENTE, id)
      if (navigator.onLine && !pendente) return remoto.reativarCliente(id)
      const atual = (await locaisClientes()).find((item) => item.id === id)
      if (!atual) throw new Error('Cliente não encontrado no cadastro local.')
      await enfileirar('cliente.reativar', id, {}, dependeDe, versaoLida(atual))
    },
  }
}
