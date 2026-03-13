import React, { useEffect, useMemo, useState } from 'https://esm.sh/react@18.3.1';
import { createRoot } from 'https://esm.sh/react-dom@18.3.1/client';

const h = React.createElement;
const API = '/api/miniapp';
const tg = window.Telegram?.WebApp;
const getUserId = () => tg?.initDataUnsafe?.user?.id || Number(new URLSearchParams(location.search).get('userId') || 1);

function App() {
  const userId = useMemo(getUserId, []);
  const [data, setData] = useState();
  const [activeWithdraws, setActiveWithdraws] = useState([]);
  const [tab, setTab] = useState('race');
  const [message, setMessage] = useState('');
  const [spark, setSpark] = useState(null);
  const [battleEmoji, setBattleEmoji] = useState('');
  const [favoriteDraft, setFavoriteDraft] = useState('');
  const [voteAmount, setVoteAmount] = useState(10);
  const [topupAmount, setTopupAmount] = useState(100);
  const [withdrawAmount, setWithdrawAmount] = useState(100);

  const refresh = async () => {
    const [bootstrapRes, withdrawsRes] = await Promise.all([
      fetch(`${API}/bootstrap?userId=${userId}`),
      fetch(`${API}/withdraw/active?userId=${userId}`)
    ]);
    const bootstrapData = await bootstrapRes.json();
    setData(bootstrapData);
    const withdrawData = await withdrawsRes.json();
    setActiveWithdraws(withdrawData.items || []);

    if (!favoriteDraft && bootstrapData.favoriteEmoji) setFavoriteDraft(bootstrapData.favoriteEmoji);
    if (!favoriteDraft && bootstrapData.allEmojis?.length) setFavoriteDraft(bootstrapData.allEmojis[0]);
    if (!battleEmoji && bootstrapData.allEmojis?.length) setBattleEmoji(bootstrapData.allEmojis[0]);
  };

  useEffect(() => {
    tg?.ready();
    refresh();
  }, []);

  const act = async (path, body) => {
    const r = await (await fetch(`${API}/${path}?userId=${userId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })).json();
    setMessage(r.message);
    if (r.invoiceLink) {
      tg?.openLink ? tg.openLink(r.invoiceLink) : window.open(r.invoiceLink, '_blank');
    }
    await refresh();
    return r;
  };

  if (!data) return h('div', { className: 'loading' }, 'Загрузка…');

  const canVote = data.balance >= voteAmount && voteAmount > 0;

  const raceUnits = (data.race?.units || []).map(u => h('div', { className: 'unit', key: u.playerNumber },
    h('div', { className: 'unit-head' },
      h('div', { className: 'name' }, u.playerName),
      h('div', { className: 'score' }, u.score)
    ),
    h('div', { className: 'meter' }, h('div', { style: { width: `${Math.min(100, u.score)}%` } })),
    spark === u.playerNumber && h('div', { className: 'spark' }, '✨'),
    h('div', { className: 'actions' },
      h('button', { disabled: !canVote, onClick: () => act('vote', { matchId: data.race.matchId, playerNumber: u.playerNumber, amount: voteAmount }) }, `Голос ${voteAmount}⭐`),
      h('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐇'),
      h('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐢'),
      h('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🛡')
    )
  ));

  return h('div', { className: 'app' },
    h('div', { className: 'aurora' }),
    h('header', { className: 'top-card' },
      h('div', null, h('span', { className: 'label' }, 'Баланс'), h('b', null, `${data.balance} ⭐`)),
      h('div', null, h('span', { className: 'label' }, 'Бесплатные бустеры'), h('b', null, data.freeBoosters))
    ),
    h('nav', { className: 'tabs' },
      h('button', { className: tab === 'race' ? 'active' : '', onClick: () => setTab('race') }, 'Гонка'),
      h('button', { className: tab === 'account' ? 'active' : '', onClick: () => setTab('account') }, 'Аккаунт')
    ),
    tab === 'race' && h('section', { className: 'panel' },
      h('h2', null, data.race ? `Гонка #${data.race.matchId}` : 'Нет активной гонки'),
      h('p', { className: 'subtitle' }, data.race ? `Тип: ${data.race.type} · Статус: ${data.race.status}` : 'Ожидаем запуск следующего матча.'),
      h('div', { className: 'row' }, h('input', { className: 'field', type: 'number', min: 1, value: voteAmount, onChange: e => setVoteAmount(Number(e.target.value || 1)) })),
      ...raceUnits
    ),
    tab === 'account' && h('section', { className: 'panel' },
      h('h2', null, 'Профиль и действия'),
      h('h3', null, 'Любимый смайл'),
      h('div', { className: 'picker' },
        h('select', { value: favoriteDraft, onChange: e => setFavoriteDraft(e.target.value) }, ...(data.allEmojis || []).map(e => h('option', { key: e }, e))),
        h('button', { onClick: () => act('favorite', { playerName: favoriteDraft }) }, 'Сохранить')
      ),
      h('p', { className: 'subtitle' }, `Текущий: ${data.favoriteEmoji || 'не выбран'}. Перевыбор — 150⭐.`),
      h('h3', null, 'Очередь'),
      h('div', { className: 'row' },
        h('button', { disabled: !data.favoriteEmoji, onClick: () => act('queue', { playerName: data.favoriteEmoji }) }, 'В очередь любимый смайл (10⭐)')
      ),
      h('h3', null, 'Баланс'),
      h('div', { className: 'row' },
        h('input', { className: 'field', type: 'number', min: 1, value: topupAmount, onChange: e => setTopupAmount(Number(e.target.value || 1)) }),
        h('button', { onClick: () => act('topup', { amount: topupAmount }) }, 'Пополнить через ⭐')
      ),
      h('h3', null, 'Вывод'),
      h('div', { className: 'row' },
        h('input', { className: 'field', type: 'number', min: 100, value: withdrawAmount, onChange: e => setWithdrawAmount(Number(e.target.value || 100)) }),
        h('button', { onClick: () => act('withdraw', { amount: withdrawAmount }) }, 'Создать запрос')
      ),
      h('div', { className: 'grid grid-2' }, ...activeWithdraws.map(w => h('button', {
        key: w.id,
        className: 'chip',
        onClick: () => act('withdraw/cancel', { requestId: w.id })
      }, `Отменить вывод #${w.id} · ${w.amount}⭐`))),
      h('h3', null, 'Батл'),
      h('div', { className: 'picker' },
        h('select', { value: battleEmoji, onChange: e => setBattleEmoji(e.target.value) }, ...(data.allEmojis || []).map(e => h('option', { key: e }, e))),
        h('button', { onClick: () => act('battle', { playerName: battleEmoji, stake: 100 }) }, 'Создать батл 100⭐')
      ),
      h('button', { className: 'help-btn', onClick: async () => setMessage((await (await fetch(`${API}/help`)).json()).message) }, 'Помощь')
    ),
    message && h('footer', null, message)
  );
}

createRoot(document.getElementById('root')).render(h(App));
