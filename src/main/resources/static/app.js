import React, { useEffect, useMemo, useState } from 'https://esm.sh/react@18.3.1';
import { createRoot } from 'https://esm.sh/react-dom@18.3.1/client';

const h = React.createElement;
const API = '/api/miniapp';
const tg = window.Telegram?.WebApp;
const WITHDRAW_MIN = 100;
const TOPUP_MIN = 1;
const POLL_INTERVAL_MS = 3500;
const DEFAULT_TRACK_LENGTH = 62;

const RACE_TYPE_LABELS = {
  REGULAR: 'Обычная',
  SPRINT: 'Спринт',
  MARATHON: 'Марафон'
};

const TRACK_THEME_LABELS = {
  asphalt: 'Трасса',
  grass: 'Газон',
  desert: 'Пустыня'
};

const TRACK_THEMES = ['asphalt', 'grass', 'desert'];

const telegramUser = tg?.initDataUnsafe?.user || null;
const telegramUserId = telegramUser?.id || null;

const getTelegramAccountLabel = (user) => {
  if (!user) return 'Не удалось определить Telegram-аккаунт';
  if (user.username) return `@${user.username}`;
  const fullName = [user.first_name, user.last_name].filter(Boolean).join(' ').trim();
  return fullName || `ID ${user.id}`;
};

const getUserId = () => telegramUserId || Number(new URLSearchParams(location.search).get('userId') || 1);

const normalizeType = (type) => String(type || '').trim().toUpperCase();
const getRaceTypeLabel = (type) => RACE_TYPE_LABELS[normalizeType(type)] || type || 'Неизвестно';
const getTrackTheme = (race) => {
  if (!race) return TRACK_THEMES[0];
  const seed = Number(race.matchId || 0);
  return TRACK_THEMES[Math.abs(seed) % TRACK_THEMES.length];
};

function App() {
  const userId = useMemo(getUserId, []);
  const [data, setData] = useState();
  const [activeWithdraws, setActiveWithdraws] = useState([]);
  const [tab, setTab] = useState('race');
  const [message, setMessage] = useState('');
  const [spark, setSpark] = useState(null);
  const [battleEmoji, setBattleEmoji] = useState('');
  const [voteInputs, setVoteInputs] = useState({});
  const [topupAmount, setTopupAmount] = useState(100);
  const [withdrawAmount, setWithdrawAmount] = useState(100);
  const [favoriteIndex, setFavoriteIndex] = useState(0);

  const requestQuery = useMemo(() => new URLSearchParams({ userId: String(userId) }).toString(), [userId]);
  const requestHeaders = useMemo(() => {
    const headers = {};
    if (telegramUserId) headers['X-Telegram-User-Id'] = String(telegramUserId);
    return headers;
  }, []);

  const refresh = async (silent = false, partial = false) => {
    const bootstrapRes = await fetch(`${API}/bootstrap?${requestQuery}`, { headers: requestHeaders });
    const bootstrapData = await bootstrapRes.json();
    if (partial) {
      setData(current => current ? {
        ...current,
        balance: bootstrapData.balance,
        freeBoosters: bootstrapData.freeBoosters,
        race: bootstrapData.race
      } : bootstrapData);
    } else {
      setData(bootstrapData);
      const withdrawRes = await fetch(`${API}/withdraw/active?${requestQuery}`, { headers: requestHeaders });
      const withdrawData = await withdrawRes.json();
      setActiveWithdraws(withdrawData.items || []);
    }
    if (!silent && !bootstrapData.race && tab === 'race') {
      setMessage('Сейчас нет активной гонки. Обновим автоматически, как только стартует следующая.');
    }
  };

  useEffect(() => {
    tg?.ready();
    refresh();
  }, []);

  useEffect(() => {
    const timer = setInterval(() => {
      refresh(true, true).catch(() => null);
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [userId]);

  useEffect(() => {
    if (!data?.allEmojis?.length) return;
    setBattleEmoji(current => current || data.allEmojis[0]);
    const currentFavorite = data.favoriteEmoji || data.allEmojis[0];
    const idx = Math.max(0, data.allEmojis.indexOf(currentFavorite));
    setFavoriteIndex(idx);
  }, [data?.favoriteEmoji, data?.allEmojis]);

  const act = async (path, body) => {
    const r = await (await fetch(`${API}/${path}?${requestQuery}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...requestHeaders },
      body: JSON.stringify(body)
    })).json();
    setMessage(r.message);
    if (r.invoiceLink) {
      tg?.openLink ? tg.openLink(r.invoiceLink) : window.open(r.invoiceLink, '_blank');
    }
    await refresh(true);
    return r;
  };

  const openHelp = async () => {
    const response = await fetch(`${API}/help`);
    const payload = await response.json();
    const link = payload?.message;
    if (link && /^https?:\/\//.test(link)) {
      tg?.openLink ? tg.openLink(link) : window.open(link, '_blank');
      return;
    }
    setMessage('Ссылка на поддержку временно недоступна.');
  };

  if (!data) return h('div', { className: 'loading' }, 'Загрузка…');

  const raceEnded = !data.race || data.race.status !== 'CREATED';
  const raceBeforeStart = data.race?.status === 'CREATED';
  const boostersDisabled = !data.race || raceBeforeStart;
  const maxWithdraw = data?.balance || WITHDRAW_MIN;
  const clampedWithdraw = Math.max(WITHDRAW_MIN, Math.min(withdrawAmount || WITHDRAW_MIN, maxWithdraw));

  const raceUnits = data.race?.units || [];
  const maxScore = raceUnits.reduce((max, u) => Math.max(max, Number(u.score) || 0), 0);
  const finishScore = Math.max(Number(data.race?.trackLength) || DEFAULT_TRACK_LENGTH, maxScore, 1);
  const trackTheme = getTrackTheme(data.race);

  const raceRows = raceUnits.map((u, index) => {
    const score = Number(u.score) || 0;
    const percent = Math.max(0, Math.min(100, Math.round((score / finishScore) * 100)));

    return h('div', { className: 'unit lane', key: u.playerNumber },
      h('div', { className: 'unit-head' },
        h('div', { className: 'name' }, u.playerName),
        h('div', { className: 'score' }, `${percent}%`)
      ),
      h('div', { className: 'meter' },
        h('div', { className: 'meter-fill', style: { width: `${percent}%` } }),
        h('div', { className: 'runner', style: { left: `${percent}%` } }, u.playerName),
        h('div', { className: 'finish-line' })
      ),
      spark === u.playerNumber && h('div', { className: 'spark' }, '✨'),
      raceBeforeStart && h('div', { className: 'vote-inline' },
        h('input', {
          className: 'field',
          type: 'number',
          min: 1,
          max: Math.max(1, data.balance || 1),
          step: 1,
          value: voteInputs[u.playerNumber] ?? 1,
          onChange: e => {
            const raw = Number(e.target.value || 1);
            const next = Math.max(1, Math.min(raw, Math.max(1, data.balance || 1)));
            setVoteInputs(current => ({ ...current, [u.playerNumber]: next }));
          }
        }),
        h('button', {
          disabled: !data.balance || data.balance < 1,
          onClick: () => act('vote', { matchId: data.race.matchId, playerNumber: u.playerNumber, amount: voteInputs[u.playerNumber] ?? 1 })
        }, 'Отдать голос')
      ),
      !raceBeforeStart && h('div', { className: 'booster-actions' },
        h('button', {
          className: 'booster booster-bust',
          disabled: boostersDisabled,
          onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); }
        }, h('span', null, '🐇'), ' ДЛЯ ', u.playerName),
        h('button', {
          className: 'booster booster-slow',
          disabled: boostersDisabled,
          onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); }
        }, h('span', null, '🐢'), ' ДЛЯ ', u.playerName),
        h('button', {
          className: 'booster booster-shield',
          disabled: boostersDisabled,
          onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); }
        }, h('span', null, '🪖'), ' ДЛЯ ', u.playerName)
      )
    );
  });

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
    tab === 'race' && h('section', { className: `panel race-panel race-theme-${trackTheme}` },
      h('h2', null, data.race ? `Гонка #${data.race.matchId} · ${getRaceTypeLabel(data.race.type)}` : 'Нет активной гонки'),
      data.race && h('p', { className: 'race-theme-label' }, `Стиль: ${TRACK_THEME_LABELS[trackTheme]}`),
      data.race && h('p', { className: 'race-intro' }, `🔥 Гонка в самом разгаре! 🔥\nПомоги своему фавориту придти на 🏁 первым!\n\nИспользуй бустеры на кнопках ниже:\n 🐇 (10⭐️) - временно ускоряет выбранный смайл\n 🐢 (10⭐️) - временно замедляет выбранный смайл\n 🪖 (40⭐️) - позволяет защититься от 5-ти 🐢`),
      data.race && raceEnded && h('p', { className: 'badge' }, 'Голосование закрыто, но бустеры активны.'),
      h('div', { className: `track track-${trackTheme}` }, ...raceRows)
    ),
    tab === 'account' && h('section', { className: 'panel' },
      h('h2', null, 'Профиль и действия'),
      h('h3', null, 'Текущий Telegram-аккаунт'),
      h('p', { className: 'subtitle' }, `${getTelegramAccountLabel(telegramUser)}${telegramUser?.id ? ` · ID ${telegramUser.id}` : ''}`),
      h('p', { className: 'subtitle' }, data.localTestMode
        ? `Тестовый режим: баланс берётся из аккаунта владельца (ID ${data.userId}).`
        : `Баланс и операции выполняются для текущего аккаунта (ID ${data.userId}).`),
      h('h3', null, 'Любимый смайл'),
      h('div', { className: 'emoji-slider-wrap' },
        h('input', {
          type: 'range',
          min: 0,
          max: Math.max(0, data.allEmojis.length - 1),
          value: favoriteIndex,
          onChange: e => setFavoriteIndex(Number(e.target.value)),
          className: 'emoji-slider'
        }),
        h('div', { className: 'emoji-preview' }, data.allEmojis[favoriteIndex]),
        h('div', { className: 'slider-meta' }, `Смайл ${favoriteIndex + 1} из ${data.allEmojis.length}`),
        h('div', { className: 'row slider-controls' },
          h('button', { disabled: favoriteIndex <= 0, onClick: () => setFavoriteIndex(Math.max(0, favoriteIndex - 1)) }, '← Назад'),
          h('button', { disabled: favoriteIndex >= data.allEmojis.length - 1, onClick: () => setFavoriteIndex(Math.min(data.allEmojis.length - 1, favoriteIndex + 1)) }, 'Вперёд →')
        ),
        h('div', { className: 'row' },
          h('button', { onClick: () => act('favorite', { playerName: data.allEmojis[favoriteIndex] }) }, 'Сохранить любимый смайл')
        )
      ),
      h('p', { className: 'subtitle' }, 'Смена любимого смайла — 150⭐ (первый выбор бесплатный).'),
      h('h3', null, 'Управление очередью'),
      h('div', { className: 'row' },
        h('button', { disabled: !data.favoriteEmoji, onClick: () => act('queue', { playerName: data.favoriteEmoji }) }, 'Добавить любимый смайл в очередь (10⭐)')
      ),
      h('h3', null, 'Баланс'),
      h('div', { className: 'row' },
        h('input', { className: 'field', type: 'number', min: TOPUP_MIN, step: 1, value: topupAmount, onChange: e => setTopupAmount(Math.max(TOPUP_MIN, Number(e.target.value || TOPUP_MIN))) }),
        h('button', { onClick: () => act('topup', { amount: topupAmount }) }, 'Пополнить через ⭐')
      ),
      h('h3', null, 'Вывод'),
      h('div', { className: 'row' },
        h('input', {
          className: 'field',
          type: 'number',
          min: WITHDRAW_MIN,
          max: maxWithdraw,
          step: 1,
          value: withdrawAmount,
          onChange: e => setWithdrawAmount(Number(e.target.value || WITHDRAW_MIN))
        }),
        h('button', { disabled: clampedWithdraw > maxWithdraw, onClick: () => act('withdraw', { amount: clampedWithdraw }) }, 'Создать запрос на вывод')
      ),
      h('p', { className: 'subtitle' }, `Доступно к выводу: до ${maxWithdraw} ⭐. Минимум: ${WITHDRAW_MIN} ⭐.`),
      h('div', { className: 'grid' }, ...activeWithdraws.map(w => h('button', {
        key: w.id,
        className: 'chip',
        onClick: () => act('withdraw/cancel', { requestId: w.id })
      }, `Отменить вывод #${w.id} (${w.amount}⭐)`))),
      h('h3', null, 'Батл'),
      h('div', { className: 'row' },
        h('select', { value: battleEmoji, onChange: e => setBattleEmoji(e.target.value) }, ...(data.allEmojis || []).map(e => h('option', { key: e }, e))),
        h('button', { onClick: () => act('battle', { playerName: battleEmoji, stake: 100 }) }, 'Создать батл 100⭐')
      ),
      h('button', { className: 'help-btn', onClick: openHelp }, 'Связаться с поддержкой')
    ),
    message && h('div', { className: 'toast' }, message)
  );
}

createRoot(document.getElementById('root')).render(h(App));
