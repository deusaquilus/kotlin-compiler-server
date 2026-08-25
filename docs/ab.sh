#!/usr/bin/env bash
# ab.sh <workloadA> <workloadB> <n> <reps> [warmupMs] [measureMs]
# Forks a FRESH JVM per measurement (kills profile pollution) and INTERLEAVES
# A,B,A,B... so slow drift (thermal, noisy neighbour) cancels in the ratio.
A=$1; B=$2; N=$3; REPS=${4:-11}; W=${5:-300}; M=${6:-600}
DIR="$(cd "$(dirname "$0")" && pwd)"
as=(); bs=()
for ((i=0;i<REPS;i++)); do
  as+=( "$(java -cp "$DIR" Bench "$A" "$N" "$W" "$M")" )
  bs+=( "$(java -cp "$DIR" Bench "$B" "$N" "$W" "$M")" )
done
printf '%s\n' "${as[@]}" > /tmp/.a$$; printf '%s\n' "${bs[@]}" > /tmp/.b$$
paste /tmp/.a$$ /tmp/.b$$ | awk -v A="$A" -v B="$B" -v N="$N" '
  { a[NR]=$1; b[NR]=$2; r[NR]=$1/$2; sr+=r[NR]; sa+=$1; sb+=$2 }
  END {
    n=NR; mr=sr/n; ma=sa/n; mb=sb/n
    for(i=1;i<=n;i++){ d=r[i]-mr; v+=d*d }
    sd=(n>1)?sqrt(v/(n-1)):0
    asort_n=n
    # median ratio
    for(i=1;i<=n;i++) s[i]=r[i]
    for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if(s[j]<s[i]){t=s[i];s[i]=s[j];s[j]=t}
    med=(n%2)?s[(n+1)/2]:(s[n/2]+s[n/2+1])/2
    printf "n=%-9s %-9s %9.1f ns   %-9s %9.1f ns\n", N, A, ma, B, mb
    printf "          ratio %s/%s  mean %.4f  median %.4f  sd %.4f  CV %.2f%%  min %.4f  max %.4f  (reps=%d)\n", A, B, mr, med, sd, 100*sd/mr, s[1], s[n], n
  }'
rm -f /tmp/.a$$ /tmp/.b$$
