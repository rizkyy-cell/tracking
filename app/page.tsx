'use client';

import { useEffect, useRef, useState } from 'react';

export default function Page() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [status, setStatus] = useState('Idle');

  useEffect(() => {
    let camera: any;
    let hands: any;
    let mounted = true;

    const start = async () => {
      const [{ Hands }, { Camera }, drawingUtils] = await Promise.all([
        import('@mediapipe/hands'),
        import('@mediapipe/camera_utils'),
        import('@mediapipe/drawing_utils'),
      ]);

      if (!mounted || !videoRef.current || !canvasRef.current) return;

      hands = new Hands({
        locateFile: (file: string) =>
          `https://cdn.jsdelivr.net/npm/@mediapipe/hands/${file}`,
      });

      hands.setOptions({
        maxNumHands: 2,
        modelComplexity: 1,
        minDetectionConfidence: 0.7,
        minTrackingConfidence: 0.6,
      });

      hands.onResults((results: any) => {
        const canvas = canvasRef.current;
        const video = videoRef.current;
        if (!canvas || !video) return;

        const ctx = canvas.getContext('2d');
        if (!ctx) return;

        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;

        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(results.image, 0, 0, canvas.width, canvas.height);

        if (results.multiHandLandmarks?.length) {
          setStatus(`Detected: ${results.multiHandLandmarks.length} hand(s)`);

          for (const landmarks of results.multiHandLandmarks) {
            drawingUtils.drawConnectors(ctx, landmarks, Hands.HAND_CONNECTIONS, {
              color: '#22c55e',
              lineWidth: 3,
            });
            drawingUtils.drawLandmarks(ctx, landmarks, {
              color: '#ffffff',
              lineWidth: 1,
              radius: 3,
            });
          }
        } else {
          setStatus('No hand detected');
        }
      });

      camera = new Camera(videoRef.current, {
        onFrame: async () => {
          if (videoRef.current) await hands.send({ image: videoRef.current });
        },
        width: 1280,
        height: 720,
      });

      camera.start();
      setStatus('Camera active');
    };

    start();

    return () => {
      mounted = false;
      camera?.stop?.();
    };
  }, []);

  return (
    <main className="min-h-screen bg-slate-950 text-white flex items-center justify-center p-6">
      <div className="w-full max-w-6xl grid gap-6 lg:grid-cols-[2fr,1fr]">
        <section className="rounded-3xl bg-slate-900 border border-slate-800 p-4 shadow-2xl">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h1 className="text-2xl font-semibold">Tracking</h1>
              <p className="text-slate-400 text-sm">MediaPipe Hands real-time tracker</p>
            </div>
            <span className="rounded-full bg-emerald-500/15 text-emerald-400 px-3 py-1 text-sm">
              {status}
            </span>
          </div>

          <div className="relative overflow-hidden rounded-2xl bg-black aspect-video">
            <video
              ref={videoRef}
              className="absolute inset-0 w-full h-full object-cover opacity-0"
              playsInline
              muted
              autoPlay
            />
            <canvas ref={canvasRef} className="w-full h-full" />
          </div>
        </section>

        <aside className="rounded-3xl bg-slate-900 border border-slate-800 p-6">
          <h2 className="text-lg font-medium mb-3">Project Notes</h2>
          <ul className="space-y-3 text-slate-300 text-sm list-disc pl-5">
            <li>Next.js + TypeScript.</li>
            <li>Deploy-ready for Vercel.</li>
            <li>GitHub-friendly structure.</li>
            <li>Tracking 21 hand landmarks.</li>
          </ul>
        </aside>
      </div>
    </main>
  );
        }
