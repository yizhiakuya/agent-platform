import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { QRCodeSVG } from 'qrcode.react';
import { api, EnrollmentResponse } from '../api/client';

export default function DevicesPage() {
  const qc = useQueryClient();
  const devices = useQuery({ queryKey: ['devices'], queryFn: api.listDevices });
  const [enrollment, setEnrollment] = useState<EnrollmentResponse | null>(null);

  const create = useMutation({
    mutationFn: api.createEnrollment,
    onSuccess: (data) => {
      setEnrollment(data);
      qc.invalidateQueries({ queryKey: ['devices'] });
    }
  });

  return (
    <div className="space-y-6">
      <section>
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="page-title">设备</h1>
            <p className="page-subtitle">管理已绑定的 Android agent 客户端。</p>
          </div>
          <button
            onClick={() => create.mutate()}
            disabled={create.isPending}
            className="btn-primary"
          >
            + 新增设备
          </button>
        </div>

        {devices.isLoading && <div className="status-muted">加载中...</div>}
        {devices.error && <div className="status-error">错误:{String(devices.error)}</div>}
        {devices.data && devices.data.length === 0 && (
          <div className="status-muted">
            还没有设备。点击 <strong>新增设备</strong>,然后在安卓 app 扫码或粘贴 token 完成绑定。
          </div>
        )}
        {devices.data && devices.data.length > 0 && (
          <ul className="page-panel divide-y divide-slate-100 overflow-hidden">
            {devices.data.map(d => (
              <li key={d.id} className="flex flex-col gap-2 px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0">
                  <div className="truncate font-medium text-slate-950">{d.name}</div>
                  <div className="mt-0.5 text-slate-500">
                    {d.model || '?'} · {d.osVersion ? `Android ${d.osVersion}` : ''}
                  </div>
                </div>
                <div className="shrink-0 text-slate-400">
                  {d.lastSeenAt ? `最近在线 ${new Date(d.lastSeenAt).toLocaleString()}` : '从未连接'}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {enrollment && (
        <section className="page-panel p-4">
          <h2 className="font-medium text-slate-950">绑定新设备</h2>
          <div className="grid grid-cols-1 md:grid-cols-[auto,1fr] gap-4 items-start">
            <div className="rounded-md border border-slate-200 bg-white p-3">
              <QRCodeSVG value={enrollment.qrPayload} size={180} />
            </div>
            <div className="text-sm space-y-2">
              <p className="text-slate-600">
                打开安卓 app 扫此二维码 — 或将下方 token 粘贴到 app 的绑定页。
              </p>
              <code className="block break-all rounded-md bg-slate-100 px-2 py-1 text-xs text-slate-700">
                {enrollment.token}
              </code>
              <p className="text-slate-500">
                过期时间:<strong>{new Date(enrollment.expiresAt).toLocaleString()}</strong>。
              </p>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
