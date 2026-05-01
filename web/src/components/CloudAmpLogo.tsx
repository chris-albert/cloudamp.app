// EQ bar heights mirror app/src/main/res/drawable/ic_launcher.xml
const BAR_HEIGHTS = [20, 32, 8, 36, 24, 28, 20, 32, 12, 24, 28, 20, 36, 16, 24, 32, 20, 28, 16, 24];
const BAR_WIDTH = 1.9;
const BAR_PITCH = 2.4;
const BASELINE = 38;

export function CloudAmpLogo({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 34 34"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
      aria-hidden="true"
    >
      <defs>
        <linearGradient
          id="cloudamp-eq"
          x1="0"
          y1="-3"
          x2="0"
          y2="45"
          gradientUnits="userSpaceOnUse"
        >
          <stop offset="0%" stopColor="#ef4444" />
          <stop offset="22%" stopColor="#ef4444" />
          <stop offset="33%" stopColor="#eab308" />
          <stop offset="55%" stopColor="#22c55e" />
          <stop offset="100%" stopColor="#22c55e" />
        </linearGradient>
        <clipPath id="cloudamp-circle">
          <circle cx="17" cy="17" r="17" />
        </clipPath>
      </defs>
      <g clipPath="url(#cloudamp-circle)">
        <circle cx="17" cy="17" r="17" fill="#1e3a5f" />
        {BAR_HEIGHTS.map((h, i) => (
          <rect
            key={i}
            x={0.4 + i * BAR_PITCH - 7}
            y={BASELINE - h - 3}
            width={BAR_WIDTH}
            height={h}
            rx="0.3"
            fill="url(#cloudamp-eq)"
          />
        ))}
      </g>
    </svg>
  );
}
