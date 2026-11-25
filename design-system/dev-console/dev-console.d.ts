import type * as React from 'react';
export type Status = 'idle' | 'loading' | 'warn' | 'ok' | 'err';
export type RowState = 'started' | 'ready' | 'completed' | 'canceled' | 'backlog' | 'draft';
export type Tone = 'neutral' | 'accent' | 'ok' | 'danger' | 'started' | 'backlog';
export interface PageHeaderProps { eyebrow?: string; title: string; accent?: string; description?: React.ReactNode; children?: React.ReactNode }
export declare function PageHeader(props: PageHeaderProps): React.ReactElement;
export interface PanelProps { title: string; status?: Status; /** Word shown beside the dot; defaults to Live, Warning, Loading or Error. Hidden for idle. */ statusLabel?: string; freshness?: string; actions?: React.ReactNode; banner?: React.ReactNode; padded?: boolean; className?: string; children?: React.ReactNode }
export declare function Panel(props: PanelProps): React.ReactElement;
export interface StatusDotProps { status?: Status; label?: string }
export declare function StatusDot(props: StatusDotProps): React.ReactElement;
export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> { variant?: 'primary' | 'ghost'; size?: 'md' | 'sm' }
export declare function Button(props: ButtonProps): React.ReactElement;
export interface PillProps { tone?: Tone; state?: RowState | 'urgent' | 'high' | 'medium' | 'low' | 'none' | 'done' | 'unstarted' | 'triage'; children?: React.ReactNode }
export declare function Pill(props: PillProps): React.ReactElement;
export interface TagProps { children?: React.ReactNode }
export declare function Tag(props: TagProps): React.ReactElement;
export interface StaleBannerProps { message?: string; onRetry?: () => void; retrying?: boolean }
export declare function StaleBanner(props: StaleBannerProps): React.ReactElement;
export interface StateMessageProps { title: string; tone?: 'neutral' | 'error'; children?: React.ReactNode }
export declare function StateMessage(props: StateMessageProps): React.ReactElement;
export interface SkeletonProps { rows?: number; height?: number; widths?: string[] }
export declare function Skeleton(props: SkeletonProps): React.ReactElement;
export interface StatGridProps { children?: React.ReactNode }
export declare function StatGrid(props: StatGridProps): React.ReactElement;
export interface StatCardProps { label: string; value?: React.ReactNode }
export declare function StatCard(props: StatCardProps): React.ReactElement;
export interface ListRowProps { state?: RowState; id?: string; title: React.ReactNode; href?: string; meta?: React.ReactNode; aside?: React.ReactNode }
export declare function ListRow(props: ListRowProps): React.ReactElement;
export interface FormRowProps { onSubmit?: (e: React.FormEvent) => void; children?: React.ReactNode }
export declare function FormRow(props: FormRowProps): React.ReactElement;
export interface TextInputProps extends React.InputHTMLAttributes<HTMLInputElement> {}
export declare function TextInput(props: TextInputProps): React.ReactElement;
export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> { options: { value: string; label: string }[] }
export declare function Select(props: SelectProps): React.ReactElement;
export interface ResultCardProps { name: string; href?: string; stats?: string[]; tags?: string[] }
export declare function ResultCard(props: ResultCardProps): React.ReactElement;
export interface NoticeProps { variant?: 'caution' | 'plain'; children?: React.ReactNode }
export declare function Notice(props: NoticeProps): React.ReactElement;
export interface ActionResultProps { ok?: boolean; children?: React.ReactNode }
export declare function ActionResult(props: ActionResultProps): React.ReactElement;
export interface PageFooterProps { children?: React.ReactNode }
export declare function PageFooter(props: PageFooterProps): React.ReactElement;
declare global { interface Window { DevConsole: { PageHeader: typeof PageHeader; Panel: typeof Panel; StatusDot: typeof StatusDot; Button: typeof Button; Pill: typeof Pill; Tag: typeof Tag; StaleBanner: typeof StaleBanner; StateMessage: typeof StateMessage; Skeleton: typeof Skeleton; StatGrid: typeof StatGrid; StatCard: typeof StatCard; ListRow: typeof ListRow; FormRow: typeof FormRow; TextInput: typeof TextInput; Select: typeof Select; ResultCard: typeof ResultCard; Notice: typeof Notice; ActionResult: typeof ActionResult; PageFooter: typeof PageFooter } } }
