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
        <div className="flex items-center justify-between mb-3">
          <h1 className="text-xl font-semibold">设备</h1>
          <button
            onClick={() => create.mutate()}
            disabled={create.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white text-sm px-3 py-1.5 rounded"
          >+ 新增设备</button>
        </div>

        {devices.isLoading && <div className="text-slate-500">加载中…</div>}
        {devices.error && <div className="text-red-600">错误:{String(devices.error)}</div>}
        {devices.data && devices.data.length === 0 && (
          <div className="text-slate-500 border rounded p-4 bg-white">
            还没有设备。点击 <strong>新增设备</strong>,然后在安卓 app 扫码或粘贴 token 完成绑定。
          </div>
        )}
        {devices.data && devices.data.length > 0 && (
          <ul className="bg-white border rounded divide-y">
            {devices.data.map(d => (
              <li key={d.id} className="px-4 py-3 flex items-center justify-between text-sm">
                <div>
                  <div className="font-medium">{d.name}</div>
                  <div className="text-slate-500">
                    {d.model || '?'} · {d.osVersion ? `Android ${d.osVersion}` : ''}
                  </div>
                </div>
                <div className="text-slate-400">
                  {d.lastSeenAt ? `最近在线 ${new Date(d.lastSeenAt).toLocaleString()}` : '从未连接'}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {enrollment && (
        <section className="bg-white border rounded p-4">
          <h2 className="font-medium mb-3">绑定新设备</h2>
          <div className="grid grid-cols-1 md:grid-cols-[auto,1fr] gap-4 items-start">
            <div className="bg-white p-3 border rounded">
              <QRCodeSVG value={enrollment.qrPayload} size={180} />
            </div>
            <div className="text-sm space-y-2">
              <p className="text-slate-600">
                打开安卓 app 扫此二维码 — 或将下方 token 粘贴到 app 的绑定页。
              </p>
              <code className="block bg-slate-100 px-2 py-1 rounded break-all">
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
