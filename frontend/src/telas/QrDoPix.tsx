import { QRCodeSVG } from 'qrcode.react'
import { Link } from 'react-router'
import type { Identidade } from '../sessao/Identidade'
import { montarBrCode } from './brCode'

type Props = {
  chave: string
  nome: string
  cidade: string
  valor: number
}

/** O QR do valor, com o texto para quem prefere colar no aplicativo do banco. */
export function QrDoPix({ chave, nome, cidade, valor }: Props) {
  const codigo = montarBrCode(chave, nome, cidade, valor)
  if (!codigo) return null
  return <div className="qr-pix">
    <div className="qr-pix__imagem" role="img" aria-label="QR Pix do valor">
      <QRCodeSVG value={codigo} size={220} />
    </div>
    <p className="qr-pix__ajuda">Mostre o QR ao cliente. Lance o Pix só depois de ver o dinheiro no
      aplicativo do banco: o QR não avisa o sistema quando o cliente paga.</p>
    <p>Pix copia e cola: <code className="qr-pix__codigo">{codigo}</code></p>
  </div>
}

/**
 * O que o Pix manual mostra junto do valor: o QR, quando a Conta tem chave, ou, sem ela, a linha
 * que leva o administrador a cadastrá-la. O operador não vê nada a mais, porque não teria como
 * resolver; o Pix manual é lançado do mesmo jeito, sem QR.
 *
 * @param valor nulo enquanto o valor digitado não é um número
 */
export function PixManual({ identidade, valor }: { identidade: Identidade | null; valor: number | null }) {
  if (!identidade) return null
  if (identidade.chavePix && identidade.cidadePix) {
    if (valor === null) return null
    return <QrDoPix chave={identidade.chavePix} nome={identidade.nomeNegocio}
      cidade={identidade.cidadePix} valor={valor} />
  }
  if (identidade.perfil !== 'ADMIN') return null
  return <p className="qr-pix__ajuda">Cadastre a chave Pix em <Link to="/configuracao">Configuração</Link>
    {' '}para mostrar ao cliente o QR do valor.</p>
}
