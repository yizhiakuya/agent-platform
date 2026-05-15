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

  const onlineCount = devices.data?.filter(d => d.lastSeenAt && Date.now() - new Date(d.lastSeenAt).getTime() < 5 * 60 * 1000).length ?? 0;

  return (
    <div className="workbench grid lg:grid-cols-[minmax(0,1fr)_22rem]">
      <section className="page-surface min-w-0 overflow-hidden">
        <div className="panel-header">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <div className="section-title">Devices</div>
              <h1 className="mt-1 page-title">设备管理</h1>
              <p className="page-subtitle">管理 Android agent 客户端和绑定入口。</p>
            </div>
            <button
              onClick={() => create.mutate()}
              disabled={create.isPending}
              className="btn-accent"
            >
              {create.isPending ? '生成中...' : '新增设备'}
            </button>
          </div>
        </div>

        <div className="grid gap-3 border-b border-slate-200 bg-slate-50/80 p-4 sm:grid-cols-3">
          <StatCard label="已绑定" value={String(devices.data?.length ?? 0)} />
          <StatCard label="最近在线" value={String(onlineCount)} accent="text-emerald-600" />
          <StatCard label="绑定状态" value={enrollment ? '待扫码' : '正常'} />
        </div>

        <div className="p-4">
          {devices.isLoading && <div className="status-muted">加载中...</div>}
          {devices.error && <div className="status-error">错误: {String(devices.error)}</div>}
          {devices.data && devices.data.length === 0 && (
            <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-4 py-10 text-center">
              <div className="text-lg font-semibold text-slate-950">还没有设备</div>
              <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-500">
                创建绑定码后，在 Android 客户端扫码或粘贴 token 完成连接。
              </p>
            </div>
          )}
          {devices.data && devices.data.length > 0 && (
            <ul className="grid gap-3">
              {devices.data.map(d => {
                const seenAt = d.lastSeenAt ? new Date(d.lastSeenAt) : null;
                const online = seenAt ? Date.now() - seenAt.getTime() < 5 * 60 * 1000 : false;
                return (
                  <li key={d.id} className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className={['h-2 w-2 rounded-full', online ? 'bg-emerald-500' : 'bg-slate-300'].join(' ')} />
                          <div className="truncate text-base font-semibold text-slate-950">{d.name}</div>
                        </div>
                        <div className="mt-1 text-sm text-slate-500">
                          {d.model || '未知型号'} · {d.osVersion ? `Android ${d.osVersion}` : '未知系统'}
                        </div>
                      </div>
                      <div className="text-sm text-slate-500">
                        {seenAt ? `最近在线 ${seenAt.toLocaleString()}` : '从未连接'}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </section>

      <aside className="page-surface overflow-hidden">
        <div className="panel-header">
          <div className="section-title">Pairing</div>
          <div className="mt-1 text-xl font-semibold text-slate-950">绑定新设备</div>
        </div>
        <div className="p-4">
          {enrollment ? (
            <div className="space-y-4">
              <div className="rounded-lg border border-slate-200 bg-white p-4">
                <QRCodeSVG value={enrollment.qrPayload} size={220} className="mx-auto" />
              </div>
              <div className="space-y-2">
                <div className="text-sm font-semibold text-slate-700">Token</div>
                <code className="block break-all rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-700">
                  {enrollment.token}
                </code>
              </div>
              <div className="rounded-md bg-slate-950 px-3 py-2 text-sm text-white">
                过期时间 {new Date(enrollment.expiresAt).toLocaleString()}
              </div>
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-4 py-8 text-center">
              <div className="text-sm font-semibold text-slate-950">尚未生成绑定码</div>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                点击新增设备后，这里会展示二维码和 token。
              </p>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}

function StatCard({ label, value, accent = 'text-slate-950' }: { label: string; value: string; accent?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <div className="text-xs font-semibold uppercase text-slate-500">{label}</div>
      <div className={['mt-2 text-2xl font-semibold', accent].join(' ')}>{value}</div>
    </div>
  );
}
