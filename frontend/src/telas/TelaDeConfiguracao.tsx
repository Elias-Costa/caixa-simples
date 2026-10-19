import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { contas, type ConfiguracaoDaConta, type TipoDeChavePix } from '../api/contas'
import { sessaoAtual } from '../sessao/armazenamento'
import { temRecurso } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { CampoDeSenha } from './CampoDeSenha'
import { erroDeCadastro } from './erroDeCadastro'
import { Mensagem } from './Mensagem'

/** A aleatória vem primeiro porque é a recomendada: as outras identificam o dono a quem lê o QR. */
const TIPOS_DE_CHAVE: { tipo: TipoDeChavePix; rotulo: string }[] = [
  { tipo: 'ALEATORIA', rotulo: 'Chave aleatória' },
  { tipo: 'CPF', rotulo: 'CPF' },
  { tipo: 'CNPJ', rotulo: 'CNPJ' },
  { tipo: 'TELEFONE', rotulo: 'Celular' },
  { tipo: 'EMAIL', rotulo: 'E-mail' },
]

function rotuloDoTipo(tipo: TipoDeChavePix): string {
  return TIPOS_DE_CHAVE.find((opcao) => opcao.tipo === tipo)?.rotulo ?? tipo
}

type PropsDaChave = {
  configuracao: ConfiguracaoDaConta
  aoSalvar: (nova: ConfiguracaoDaConta) => void
}

/**
 * A chave Pix que a venda e o recebimento de fiado põem no QR do Pix manual, com a cidade do
 * recebedor. Pede a senha atual, porque a chave decide para onde vai o dinheiro do cliente; a
 * resposta fica aqui, junto do formulário, e não no topo da tela.
 */
function ChavePixDaConta({ configuracao, aoSalvar }: PropsDaChave) {
  const [tipo, setTipo] = useState<TipoDeChavePix>(configuracao.tipoDaChavePix ?? 'ALEATORIA')
  const [chave, setChave] = useState(configuracao.chavePix ?? '')
  const [cidade, setCidade] = useState(configuracao.cidadePix ?? '')
  const [senhaAtual, setSenhaAtual] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(pedido: () => Promise<ConfiguracaoDaConta>, sucesso: string) {
    setEnviando(true)
    try {
      const nova = await pedido()
      aoSalvar(nova)
      setTipo(nova.tipoDaChavePix ?? 'ALEATORIA')
      setChave(nova.chavePix ?? '')
      setCidade(nova.cidadePix ?? '')
      setSenhaAtual('')
      setAviso(sucesso)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setEnviando(false)
    }
  }

  function salvar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setErro(null)
    setAviso(null)
    const problema = !chave.trim() ? 'Informe a chave Pix.'
      : !cidade.trim() ? 'Informe a cidade do recebedor.'
        : !senhaAtual ? 'Informe a senha atual para confirmar.' : null
    if (problema) {
      setErro(problema)
      return
    }
    void enviar(() => contas.definirChavePix(senhaAtual, tipo, chave.trim(), cidade.trim()),
      'Chave Pix salva. Os outros aparelhos passam a mostrá-la quando o aplicativo for aberto de novo'
        + ' neles, com rede.')
  }

  function tirar() {
    setErro(null)
    setAviso(null)
    if (!senhaAtual) {
      setErro('Informe a senha atual para confirmar.')
      return
    }
    void enviar(() => contas.tirarChavePix(senhaAtual),
      'Chave Pix tirada. O Pix manual continua, lançado sem QR.')
  }

  return <section className="relatorios__cartao">
    <h3>Chave Pix do Pix manual</h3>
    {configuracao.chavePix && configuracao.tipoDaChavePix
      ? <p>Cadastrada: {rotuloDoTipo(configuracao.tipoDaChavePix)} <strong>{configuracao.chavePix}</strong>,
        cidade <strong>{configuracao.cidadePix}</strong>.</p>
      : <p>Nenhuma chave cadastrada. O Pix manual é lançado sem QR.</p>}
    <p>A venda e o recebimento de fiado mostram ao cliente um QR com a chave, o nome do negócio, a
      cidade e o valor. O QR não confirma o pagamento: o Pix só é lançado depois de ver o dinheiro no
      aplicativo do banco.</p>
    <p>Prefira a chave aleatória, criada no aplicativo do banco. O CPF, o celular e o e-mail
      aparecem para todo cliente que lê o QR e identificam o dono.</p>
    <form onSubmit={salvar} noValidate>
      <label className="campo"><span className="campo__rotulo">Tipo da chave</span>
        <select className="campo__entrada" value={tipo}
          onChange={(evento) => setTipo(evento.target.value as TipoDeChavePix)}>
          {TIPOS_DE_CHAVE.map((opcao) => <option key={opcao.tipo} value={opcao.tipo}>{opcao.rotulo}</option>)}
        </select>
      </label>
      <label className="campo"><span className="campo__rotulo">Chave Pix</span>
        <input className="campo__entrada" value={chave} autoComplete="off" maxLength={100}
          onChange={(evento) => setChave(evento.target.value)} />
      </label>
      <label className="campo"><span className="campo__rotulo">Cidade do recebedor</span>
        <input className="campo__entrada" value={cidade} maxLength={60}
          onChange={(evento) => setCidade(evento.target.value)} />
      </label>
      <CampoDeSenha rotulo="Senha atual, para confirmar" autoComplete="current-password"
        valor={senhaAtual} aoMudar={setSenhaAtual} />
      <Mensagem tipo="erro" texto={erro} />
      <Mensagem tipo="sucesso" texto={aviso} />
      <div className="cadastro__acoes">
        <button className="botao" disabled={enviando}>{enviando ? 'Salvando...' : 'Salvar a chave'}</button>
        {configuracao.chavePix && <button className="botao botao--secundario" type="button"
          disabled={enviando} onClick={tirar}>Tirar a chave</button>}
      </div>
    </form>
  </section>
}

export function TelaDeConfiguracao() {
  const { identidade, atualizarIdentidade } = useSessao()
  const [configuracao, setConfiguracao] = useState<ConfiguracaoDaConta | null>(null)
  const [nomeNegocio, setNomeNegocio] = useState('')
  // Qual chave está sendo salva: as outras ficam paradas, e só a que mudou diz que está salvando.
  const [salvando, setSalvando] = useState<'nome' | 'estoque' | 'nsu' | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)

  const carregar = useCallback(async () => {
    try {
      const lida = await contas.configuracao()
      setConfiguracao(lida)
      setNomeNegocio(lida.nomeNegocio)
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  /**
   * Toda mudança responde a configuração inteira, e a identidade guardada recebe o que ela traz: o
   * cabeçalho e o comprovante mostram o nome do negócio, o menu depende do estoque, e o PDV sem rede,
   * da exigência do NSU e da chave Pix do QR.
   */
  function guardarNaIdentidade(nova: ConfiguracaoDaConta) {
    const sessao = sessaoAtual()
    if (!identidade || !sessao) return
    atualizarIdentidade({
      ...identidade, nomeNegocio: nova.nomeNegocio,
      estoqueHabilitado: nova.estoqueHabilitado, nsuObrigatorio: nova.nsuObrigatorio,
      chavePix: nova.chavePix, cidadePix: nova.cidadePix,
    }, sessao)
  }

  async function mudar(chave: 'nome' | 'estoque' | 'nsu', pedido: () => Promise<ConfiguracaoDaConta>) {
    if (!identidade) return
    const sessao = sessaoAtual()
    if (!sessao) return
    setSalvando(chave)
    setErro(null)
    setAviso(null)
    try {
      const nova = await pedido()
      setConfiguracao(nova)
      guardarNaIdentidade(nova)
      if (chave === 'nome') {
        setNomeNegocio(nova.nomeNegocio)
        setAviso('Nome do negócio corrigido.')
      }
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(null)
    }
  }

  function corrigirNome(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    const nome = nomeNegocio.trim()
    if (!nome) {
      setAviso(null)
      setErro('Informe o nome do negócio.')
      return
    }
    void mudar('nome', () => contas.corrigirNomeDoNegocio(nome))
  }

  // Ligar exige o estoque do plano; desligar, não: um controle ligado pode ser desligado enquanto
  // não houver movimento, mesmo sem o plano.
  const semPlanoParaLigar = configuracao?.estoqueHabilitado === false && !!identidade
    && !temRecurso(identidade, 'ESTOQUE')

  return <section className="relatorios">
    <h2 className="titulo">Configuração da Conta</h2>
    <Mensagem tipo="erro" texto={erro} />
    <Mensagem tipo="sucesso" texto={aviso} />
    <section className="relatorios__cartao">
      <h3>Nome do negócio</h3>
      {configuracao === null ? <p>Carregando configuração...</p> :
        <form onSubmit={corrigirNome} noValidate>
          <label className="campo"><span className="campo__rotulo">Nome do negócio</span>
            <input className="campo__entrada" value={nomeNegocio} required maxLength={120}
              onChange={(evento) => setNomeNegocio(evento.target.value)} />
          </label>
          <p>É o nome que aparece no cabeçalho e no comprovante. Os outros aparelhos passam a
            mostrá-lo quando o aplicativo for aberto de novo neles, com rede.</p>
          <button className="botao" disabled={salvando !== null}>
            {salvando === 'nome' ? 'Salvando...' : 'Salvar o nome'}
          </button>
        </form>}
    </section>
    <section className="relatorios__cartao">
      <h3>Controle de estoque</h3>
      {configuracao === null ? <p>Carregando configuração...</p> : <>
        <p>{configuracao.estoqueHabilitado ? 'Ligado' : 'Desligado'}</p>
        <p>Quando ligado, vendas de produtos movimentam o saldo e o menu Estoque fica disponível.</p>
        <p>Depois do primeiro movimento de estoque, o controle não pode ser desligado.</p>
        {semPlanoParaLigar && <p>O controle de estoque faz parte do plano Completo, sem
          suspensão. Veja a tela <Link to="/plano">Plano</Link>.</p>}
        {/* Botão comum, e não interruptor: o texto diz a ação, e um interruptor seria lido como
            "Desligar controle de estoque, ligado". O estado está no parágrafo acima. */}
        <button className="botao" type="button"
          disabled={salvando !== null || semPlanoParaLigar}
          onClick={() => void mudar('estoque', () => contas.definirEstoque(!configuracao.estoqueHabilitado))}>
          {salvando === 'estoque' ? 'Salvando...'
            : configuracao.estoqueHabilitado ? 'Desligar controle de estoque' : 'Ligar controle de estoque'}
        </button>
      </>}
    </section>
    <section className="relatorios__cartao">
      <h3>NSU no pagamento em cartão</h3>
      {configuracao === null ? <p>Carregando configuração...</p> : <>
        <p>{configuracao.nsuObrigatorio ? 'Obrigatório' : 'Opcional'}</p>
        <p>O NSU é o número impresso no comprovante da maquininha. Com ele, a conferência do cartão,
          em Relatórios, bate cada pagamento com o extrato da operadora.</p>
        <p>Quando obrigatório, a venda e o recebimento de fiado em cartão só são registrados com o
          NSU. O que já foi lançado sem ele continua valendo, e a venda feita sem rede que chegar sem
          NSU aparece em Sincronização para conferência.</p>
        <button className="botao" type="button"
          disabled={salvando !== null}
          onClick={() => void mudar('nsu', () => contas.definirNsu(!configuracao.nsuObrigatorio))}>
          {salvando === 'nsu' ? 'Salvando...'
            : configuracao.nsuObrigatorio ? 'Deixar o NSU opcional' : 'Exigir o NSU no cartão'}
        </button>
      </>}
    </section>
    {configuracao === null ? null : <ChavePixDaConta configuracao={configuracao}
      aoSalvar={(nova) => { setConfiguracao(nova); guardarNaIdentidade(nova) }} />}
  </section>
}
