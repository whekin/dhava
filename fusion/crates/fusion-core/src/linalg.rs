//! Tiny fixed-size linear algebra for the EKF.
//!
//! Deliberately NOT an external crate (architecture rule: minimal deps, the
//! math is small and ours). Const-generic matrices with exactly the ops a
//! 6-state EKF needs: multiply, transpose, add/sub, scale, and a Gauss-Jordan
//! inverse used only on tiny innovation matrices (max 3x3), where pivoting
//! concerns are negligible for well-conditioned positive-definite inputs.

use std::ops::{Add, Index, IndexMut, Mul, Sub};

/// Row-major `R x C` matrix of `f64`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Mat<const R: usize, const C: usize>(pub [[f64; C]; R]);

/// Column vector alias.
pub type Vector<const N: usize> = Mat<N, 1>;

impl<const R: usize, const C: usize> Mat<R, C> {
    pub const fn zeros() -> Self {
        Mat([[0.0; C]; R])
    }

    pub fn transpose(&self) -> Mat<C, R> {
        let mut out = Mat::<C, R>::zeros();
        for r in 0..R {
            for c in 0..C {
                out.0[c][r] = self.0[r][c];
            }
        }
        out
    }

    pub fn scale(&self, k: f64) -> Self {
        let mut out = *self;
        for row in &mut out.0 {
            for v in row {
                *v *= k;
            }
        }
        out
    }
}

impl<const N: usize> Mat<N, N> {
    pub fn identity() -> Self {
        let mut m = Self::zeros();
        for i in 0..N {
            m.0[i][i] = 1.0;
        }
        m
    }

    /// Diagonal matrix from the given entries.
    pub fn diag(entries: [f64; N]) -> Self {
        let mut m = Self::zeros();
        for (i, entry) in entries.iter().enumerate() {
            m.0[i][i] = *entry;
        }
        m
    }

    /// Matrix inverse via Gauss-Jordan elimination with partial pivoting.
    ///
    /// Returns `None` when a pivot is (numerically) zero. Only used on small
    /// innovation matrices (N <= 3), so O(N^3) with no blocking is fine.
    pub fn inverse(&self) -> Option<Self> {
        let mut a = self.0;
        let mut inv = Self::identity().0;

        for col in 0..N {
            // Partial pivot: largest |value| in this column at/below the row.
            let mut pivot = col;
            for r in (col + 1)..N {
                if a[r][col].abs() > a[pivot][col].abs() {
                    pivot = r;
                }
            }
            if a[pivot][col].abs() < 1e-12 {
                return None;
            }
            a.swap(col, pivot);
            inv.swap(col, pivot);

            let p = a[col][col];
            for c in 0..N {
                a[col][c] /= p;
                inv[col][c] /= p;
            }
            for r in 0..N {
                if r == col {
                    continue;
                }
                let f = a[r][col];
                if f == 0.0 {
                    continue;
                }
                for c in 0..N {
                    a[r][c] -= f * a[col][c];
                    inv[r][c] -= f * inv[col][c];
                }
            }
        }
        Some(Mat(inv))
    }

    /// Forces exact symmetry: `(P + P^T) / 2`. Cheap covariance hygiene after
    /// update steps, prevents slow drift into asymmetry.
    pub fn symmetrize(&mut self) {
        for r in 0..N {
            for c in (r + 1)..N {
                let m = (self.0[r][c] + self.0[c][r]) / 2.0;
                self.0[r][c] = m;
                self.0[c][r] = m;
            }
        }
    }
}

impl<const N: usize> Vector<N> {
    pub const fn from_array(v: [f64; N]) -> Self {
        let mut m = [[0.0; 1]; N];
        let mut i = 0;
        while i < N {
            m[i][0] = v[i];
            i += 1;
        }
        Mat(m)
    }
}

impl<const R: usize, const C: usize> Index<(usize, usize)> for Mat<R, C> {
    type Output = f64;
    fn index(&self, (r, c): (usize, usize)) -> &f64 {
        &self.0[r][c]
    }
}

impl<const R: usize, const C: usize> IndexMut<(usize, usize)> for Mat<R, C> {
    fn index_mut(&mut self, (r, c): (usize, usize)) -> &mut f64 {
        &mut self.0[r][c]
    }
}

impl<const R: usize, const C: usize> Add for Mat<R, C> {
    type Output = Self;
    fn add(mut self, rhs: Self) -> Self {
        for r in 0..R {
            for c in 0..C {
                self.0[r][c] += rhs.0[r][c];
            }
        }
        self
    }
}

impl<const R: usize, const C: usize> Sub for Mat<R, C> {
    type Output = Self;
    fn sub(mut self, rhs: Self) -> Self {
        for r in 0..R {
            for c in 0..C {
                self.0[r][c] -= rhs.0[r][c];
            }
        }
        self
    }
}

impl<const R: usize, const K: usize, const C: usize> Mul<Mat<K, C>> for Mat<R, K> {
    type Output = Mat<R, C>;
    fn mul(self, rhs: Mat<K, C>) -> Mat<R, C> {
        let mut out = Mat::<R, C>::zeros();
        for r in 0..R {
            for k in 0..K {
                let a = self.0[r][k];
                if a == 0.0 {
                    continue;
                }
                for c in 0..C {
                    out.0[r][c] += a * rhs.0[k][c];
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn multiply_matches_hand_computed() {
        let a = Mat::<2, 3>([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]]);
        let b = Mat::<3, 2>([[7.0, 8.0], [9.0, 10.0], [11.0, 12.0]]);
        let c = a * b;
        assert_eq!(c, Mat::<2, 2>([[58.0, 64.0], [139.0, 154.0]]));
    }

    #[test]
    fn transpose_roundtrips() {
        let a = Mat::<2, 3>([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]]);
        assert_eq!(a.transpose().transpose(), a);
        assert_eq!(a.transpose().0[2][1], 6.0);
    }

    #[test]
    fn add_sub_scale() {
        let a = Mat::<2, 2>([[1.0, 2.0], [3.0, 4.0]]);
        let b = Mat::<2, 2>([[0.5, 0.5], [0.5, 0.5]]);
        assert_eq!((a + b) - b, a);
        assert_eq!(a.scale(2.0).0[1][1], 8.0);
    }

    #[test]
    fn inverse_of_3x3_gives_identity_product() {
        let a = Mat::<3, 3>([[4.0, 1.0, 0.2], [1.0, 3.0, 0.5], [0.2, 0.5, 2.0]]);
        let inv = a.inverse().expect("well-conditioned matrix");
        let prod = a * inv;
        let id = Mat::<3, 3>::identity();
        for r in 0..3 {
            for c in 0..3 {
                assert!(
                    (prod.0[r][c] - id.0[r][c]).abs() < 1e-10,
                    "prod[{r}][{c}] = {}",
                    prod.0[r][c]
                );
            }
        }
    }

    #[test]
    fn singular_matrix_has_no_inverse() {
        let a = Mat::<2, 2>([[1.0, 2.0], [2.0, 4.0]]);
        assert!(a.inverse().is_none());
    }

    #[test]
    fn identity_and_diag() {
        let d = Mat::<3, 3>::diag([1.0, 2.0, 3.0]);
        let v = Vector::<3>::from_array([1.0, 1.0, 1.0]);
        let r = d * v;
        assert_eq!(r, Vector::<3>::from_array([1.0, 2.0, 3.0]));
        assert_eq!(Mat::<3, 3>::identity() * d, d);
    }
}
