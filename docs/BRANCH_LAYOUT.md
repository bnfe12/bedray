# Branch layout

`main` is the maintained branch for the Slang AOT renderer and the native GPU scene.
Changes land through pull requests and the native Windows CI runs for pushes and pull requests targeting `main`.

The previous Java/GLSL renderer remains in Git history at commit
`9c985fa4c56894e9370e7ef8d6120d63b345ad26`. That revision is an ancestor of `main`.

See the [implementation plan](STACK_IMPLEMENTATION_PLAN.md) for the remaining renderer work and GPU acceptance checks.
