import { useEffect, useState } from 'react'

/** Se o navegador acredita ter rede. É indicação, não garantia: a chamada à API é quem sabe. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(navigator.onLine)

  useEffect(() => {
    const atualizar = () => setOnline(navigator.onLine)
    window.addEventListener('online', atualizar)
    window.addEventListener('offline', atualizar)
    return () => {
      window.removeEventListener('online', atualizar)
      window.removeEventListener('offline', atualizar)
    }
  }, [])

  return online
}
