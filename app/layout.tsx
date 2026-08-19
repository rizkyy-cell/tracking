import './global.css';
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Hand Tracking Studio',
  description: 'Professional real-time hand tracking application',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="id">
      <body>{children}</body>
    </html>
  );
}
